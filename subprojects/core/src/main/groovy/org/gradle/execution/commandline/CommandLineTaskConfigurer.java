/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.execution.commandline;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.gradle.TaskExecutionRequest;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.options.OptionDescriptor;
import org.gradle.api.internal.tasks.options.OptionReader;
import org.gradle.cli.CommandLineArgumentException;
import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.gradle.cli.ParsedCommandLineOption;
import org.gradle.internal.DefaultTaskExecutionRequest;
import org.gradle.internal.typeconversion.TypeConversionException;

import java.util.Collection;
import java.util.List;

public class CommandLineTaskConfigurer {

    private OptionReader optionReader;

    public CommandLineTaskConfigurer(OptionReader optionReader) {
        this.optionReader = optionReader;
    }

    public List<TaskExecutionRequest> configureTasks(Collection<Task> tasks, List<TaskExecutionRequest> arguments) {
        assert !tasks.isEmpty();
        if (arguments.isEmpty()) {
            return arguments;
        }
        return configureTasksNow(tasks, arguments);
    }

    private List<TaskExecutionRequest> configureTasksNow(Collection<Task> tasks, List<TaskExecutionRequest> arguments) {
        List<String> remainingArguments = null;
        List<String> argumentsOrParameters = Lists.newArrayList();
        List<TaskExecutionRequest> parameters = Lists.newArrayList();
        boolean notArgument = false;
        for (TaskExecutionRequest parameter : arguments) {
            if (parameter.getProjectPath() != null) {
                notArgument = true;
            }
            if (notArgument) {
                parameters.add(parameter);
            } else {
                argumentsOrParameters.add(parameter.getTaskName());
            }
        }
        for (Task task : tasks) {
            CommandLineParser parser = new CommandLineParser();
            final List<OptionDescriptor> commandLineOptions = optionReader.getOptions(task);
            for (OptionDescriptor optionDescriptor : commandLineOptions) {
                String optionName = optionDescriptor.getName();
                org.gradle.cli.CommandLineOption option = parser.option(optionName);
                option.hasDescription(optionDescriptor.getDescription());
                option.hasArgument(optionDescriptor.getArgumentType());
            }

            ParsedCommandLine parsed;
            try {
                parsed = parser.parse(argumentsOrParameters);
            } catch (CommandLineArgumentException e) {
                //we expect that all options must be applicable for each task
                throw new TaskConfigurationException(task.getPath(), "Problem configuring task " + task.getPath() + " from command line.", e);
            }

            for (OptionDescriptor commandLineOptionDescriptor : commandLineOptions) {
                final String name = commandLineOptionDescriptor.getName();
                if (parsed.hasOption(name)) {
                    ParsedCommandLineOption o = parsed.option(name);
                    try {
                        commandLineOptionDescriptor.apply(task, o.getValues());
                    } catch (TypeConversionException ex) {
                        throw new TaskConfigurationException(task.getPath(),
                                String.format("Problem configuring option '%s' on task '%s' from command line.", name, task.getPath()), ex);
                    }
                }
            }
            assert remainingArguments == null || remainingArguments.equals(parsed.getExtraArguments())
                    : "we expect all options to be consumed by each task so remainingArguments should be the same for each task";
            remainingArguments = parsed.getExtraArguments();
        }
        return Lists.newArrayList(Iterables.concat(
                Iterables.transform(
                        remainingArguments,
                        new Function<String, TaskExecutionRequest>() {
                            public TaskExecutionRequest apply(String input) {
                                return new DefaultTaskExecutionRequest(input);
                            }
                        }),
                parameters));
    }
}