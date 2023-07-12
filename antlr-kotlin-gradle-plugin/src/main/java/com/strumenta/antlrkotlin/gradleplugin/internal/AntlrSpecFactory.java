/*
 * Copyright 2015 the original author or authors.
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

package com.strumenta.antlrkotlin.gradleplugin.internal;

import com.strumenta.antlrkotlin.gradleplugin.AntlrKotlinTask;
import org.gradle.api.file.FileCollection;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AntlrSpecFactory {

    public AntlrSpec create(AntlrKotlinTask antlrTask, Set<File> grammarFiles,
                            Set<Object> sources) {
        List<String> arguments = new LinkedList<>(antlrTask.getArguments());
        File outputDirectory = antlrTask.getOutputDirectory();

        String packageName = antlrTask.getPackageName();
        if (packageName != null && !arguments.contains("-package")) {
            arguments.add("-package");
            arguments.add(packageName);
            outputDirectory = new File(outputDirectory, packageName.replace(".", "/"));
        }

        if (antlrTask.isTrace() && !arguments.contains("-trace")) {
            arguments.add("-trace");
        }
        if (antlrTask.isTraceLexer() && !arguments.contains("-traceLexer")) {
            arguments.add("-traceLexer");
        }
        if (antlrTask.isTraceParser() && !arguments.contains("-traceParser")) {
            arguments.add("-traceParser");
        }
        if (antlrTask.isTraceTreeWalker() && !arguments.contains("-traceTreeWalker")) {
            arguments.add("-traceTreeWalker");
        }

        Set<File> fileSources = sources.stream()
                .filter((Object element) -> element instanceof File)
                .map(element -> (File)element)
                .collect(Collectors.toSet());

        return new AntlrSpec(arguments, grammarFiles, fileSources, outputDirectory,
                antlrTask.getMaxHeapSize());
    }
}
