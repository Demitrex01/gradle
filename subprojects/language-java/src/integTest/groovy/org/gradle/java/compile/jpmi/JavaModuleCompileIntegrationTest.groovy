/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.java.compile.jpmi

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.maven.MavenModule

import static org.gradle.test.fixtures.jpms.ModuleJarFixture.moduleJar
import static org.gradle.test.fixtures.jpms.ModuleJarFixture.autoModuleJar
import static org.gradle.test.fixtures.jpms.ModuleJarFixture.traditionalJar

class JavaModuleCompileIntegrationTest extends AbstractIntegrationSpec {

    def setup() {
        buildFile << """
            plugins {
                id 'java-library'
            }

            repositories {
                maven { url '${mavenRepo.uri}' }
            }

            tasks.withType(JavaCompile) {
                modularClasspathHandling.get().inferModulePath = true
            }

            dependencies {
                api 'org:moda:1.0'
            }
        """
    }

    private MavenModule publishJavaModule(String name, String moduleInfoStatements = '') {
        mavenRepo.module('org', name, '1.0').mainArtifact(content: moduleJar(name, moduleInfoStatements)).publish()
    }

    private MavenModule publishJavaLibrary(String name) {
        mavenRepo.module('org', name, '1.0').mainArtifact(content: traditionalJar(name)).publish()
    }

    private MavenModule publishAutoModule(String name) {
        mavenRepo.module('org', name, '1.0').mainArtifact(content: autoModuleJar(name)).publish()
    }

    private consumingModuleClass(String... dependencies) {
        file('src/main/java/consumer/MainModule.java').text = """
            package consumer;

            public class MainModule {
                public void run() {
                    ${dependencies.collect { "new ${it}();"}.join('\n')}
                }
            }
        """
    }

    private consumingModuleInfo(String... statements) {
        file('src/main/java/module-info.java') << "module consumer { ${statements.collect { it + ';' }.join(' ') } }"
    }

    def "compiles a module using the module path"() {
        given:
        publishJavaModule('moda')
        consumingModuleInfo('requires moda')
        consumingModuleClass('moda.ModaClass')

        when:
        succeeds ':compileJava'

        then:
        javaClassFile('module-info.class').exists()
        javaClassFile('consumer/MainModule.class').exists()
    }

    def "compiles a non-module using the classpath"() {
        given:
        publishJavaModule('moda')
        consumingModuleClass('moda.ModaClass', 'moda.internal.ModaClassInternal')

        when:
        succeeds ':compileJava' // the internal class can be accessed

        then:
        javaClassFile('consumer/MainModule.class').exists()
    }

    def "fails module compilation for missing requires"() {
        given:
        publishJavaModule('moda')
        consumingModuleInfo()
        consumingModuleClass('moda.ModaClass')

        when:
        fails ':compileJava'

        then:
        failure.assertHasErrorOutput '(package moda is declared in module moda, but module consumer does not read it)'
    }

    def "fails module compilation for missing export"() {
        given:
        publishJavaModule('moda')
        consumingModuleInfo('requires moda')
        consumingModuleClass('moda.ModaClass', 'moda.internal.ModaClassInternal')

        when:
        fails ':compileJava'

        then:
        failure.assertHasErrorOutput '(package moda.internal is declared in module moda, which does not export it)'
    }

    def "compiles a module depending on an automatic module"() {
        given:
        publishAutoModule('moda')
        consumingModuleInfo('requires moda')
        consumingModuleClass('moda.ModaClass', 'moda.internal.ModaClassInternal')

        when:
        succeeds ':compileJava'

        then:
        javaClassFile('module-info.class').exists()
        javaClassFile('consumer/MainModule.class').exists()
    }

    def "fails module compilation if module depends on a plain Java library"() {
        given:
        publishJavaLibrary('moda')
        consumingModuleInfo()
        consumingModuleClass('moda.ModaClass')

        when:
        fails ':compileJava'

        then:
        // why does it not say but 'module consumer does not read it' in this error? (There is no module 'moda')
        failure.assertHasErrorOutput '(package moda is declared in the unnamed module, but module moda does not read it)'
    }

    def "compiles a module depending on a plain Java library when adding access to unnamed module"() {
        // This test is here to demonstrate that and how it works.
        // This is not a recommended use case so we do not plan to add something more specific to support this.
        given:
        publishJavaLibrary('moda')
        consumingModuleInfo()
        consumingModuleClass('moda.ModaClass')

        buildFile << """
            tasks.withType(JavaCompile) {
                options.compilerArgs += ['--add-reads', 'consumer=ALL-UNNAMED']
            }
        """

        when:
        succeeds ':compileJava'

        then:
        javaClassFile('module-info.class').exists()
        javaClassFile('consumer/MainModule.class').exists()
    }

    def "works with split module path and classpath"() {
        publishJavaLibrary('moda')

        // Local auto module
        settingsFile << 'include("auto-module")'
        file("auto-module/build.gradle") << """
            plugins {
                id 'java-library'
            }
            repositories {
                maven { url '${mavenRepo.uri}' }
            }
            tasks.withType(JavaCompile) {
                modularClasspathHandling.get().inferModulePath = true
            }
            dependencies {
                api 'org:moda:1.0'
            }
            tasks.jar {
                manifest.attributes('Automatic-Module-Name': 'auto')
            }

        """
        file('auto-module/src/main/java/auto/AutoClass.java')  << """
            package auto;

            public class AutoClass {
                public void m() {
                    // can access, because a auto module sees the unnamed module
                    new moda.ModaClass();
                    new moda.internal.ModaClassInternal();
                }
            }
        """

        // Local module
        buildFile << '''
            dependencies {
                implementation project(':auto-module')
            }
        '''
        consumingModuleInfo('requires auto')
        consumingModuleClass('auto.AutoClass')

        when:
        succeeds ':compileJava', '-Dorg.gradle.java.compile-classpath-packaging=true' // TODO better local auto-module support

        then:
        javaClassFile('module-info.class').exists()
        javaClassFile('consumer/MainModule.class').exists()
        file('auto-module/build/classes/java/main/auto/AutoClass.class').exists()

        when:
        consumingModuleClass('auto.AutoClass', 'moda.ModaClass')
        fails ':compileJava', '-Dorg.gradle.java.compile-classpath-packaging=true'

        then:
        failure.assertHasErrorOutput '(package moda is declared in the unnamed module, but module moda does not read it)'
    }

}
