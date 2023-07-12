/*
 * Copyright 2010 the original author or authors.
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

package com.strumenta.antlrkotlin.gradleplugin;

import com.strumenta.antlrkotlin.gradleplugin.internal.*;
import groovy.lang.Closure;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.util.PatternFilterable;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.process.internal.worker.WorkerProcessFactory;
import org.gradle.work.ChangeType;
import org.gradle.work.InputChanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Generates parsers from Antlr grammars.
 */
@CacheableTask
public class AntlrKotlinTask extends DefaultTask implements PatternFilterable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AntlrKotlinTask.class);

    private boolean trace;
    private boolean traceLexer;
    private boolean traceParser;
    private boolean traceTreeWalker;
    private List<String> arguments = new ArrayList<String>();

    private FileCollection antlrClasspath;

    private File outputDirectory;
    private String maxHeapSize;
    private String packageName;

    private ConfigurableFileCollection allSourceFiles = getProject().getObjects().fileCollection();

    @Internal
    protected PatternFilterable patternFilterable = new PatternSet();

    @SkipWhenEmpty // Marks the input incremental: https://github.com/gradle/gradle/issues/17593
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @IgnoreEmptyDirectories
    public FileCollection source = getProject().getObjects().fileCollection()
            .from((Callable<FileCollection>)() -> allSourceFiles.getAsFileTree().matching(patternFilterable));

    public void setSource(Object... sources) {
        allSourceFiles.setFrom(sources);
    }

    public void setSource(Object source) {
        allSourceFiles.setFrom(source);
    }

    /**
     * Specifies that all rules call {@code traceIn}/{@code traceOut}.
     */
    @Input
    public boolean isTrace() {
        return trace;
    }

    public void setTrace(boolean trace) {
        this.trace = trace;
    }

    /**
     * Specifies that all lexer rules call {@code traceIn}/{@code traceOut}.
     */
    @Input
    public boolean isTraceLexer() {
        return traceLexer;
    }

    public void setTraceLexer(boolean traceLexer) {
        this.traceLexer = traceLexer;
    }

    /**
     * Specifies that all parser rules call {@code traceIn}/{@code traceOut}.
     */
    @Input
    public boolean isTraceParser() {
        return traceParser;
    }

    public void setTraceParser(boolean traceParser) {
        this.traceParser = traceParser;
    }

    /**
     * Specifies that all tree walker rules call {@code traceIn}/{@code traceOut}.
     */
    @Input
    public boolean isTraceTreeWalker() {
        return traceTreeWalker;
    }

    public void setTraceTreeWalker(boolean traceTreeWalker) {
        this.traceTreeWalker = traceTreeWalker;
    }

    /**
     * The maximum heap size for the forked antlr process (ex: '1g').
     */
    @Internal
    public String getMaxHeapSize() {
        return maxHeapSize;
    }

    public void setMaxHeapSize(String maxHeapSize) {
        this.maxHeapSize = maxHeapSize;
    }

    /**
     * The package name of the generated files.
     */
    @Internal
    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void setArguments(List<String> arguments) {
        if (arguments != null) {
            this.arguments = arguments;
        }
    }

    /**
     * List of command-line arguments passed to the antlr process
     *
     * @return The antlr command-line arguments
     */
    @Input
    public List<String> getArguments() {
        return arguments;
    }

    /**
     * Returns the directory to generate the parser source files into.
     *
     * @return The output directory.
     */
    @OutputDirectory
    public File getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * Specifies the directory to generate the parser source files into.
     *
     * @param outputDirectory The output directory. Must not be null.
     */
    public void setOutputDirectory(File outputDirectory) {
        if (!outputDirectory.isAbsolute()) {
            outputDirectory = new File(getProject().getProjectDir(), outputDirectory.getPath());
        }
        this.outputDirectory = outputDirectory;
    }

    /**
     * Returns the classpath containing the Ant ANTLR task implementation.
     *
     * @return The Ant task implementation classpath.
     */
    @Classpath
    public FileCollection getAntlrClasspath() {
        return antlrClasspath;
    }

    /**
     * Specifies the classpath containing the Ant ANTLR task implementation.
     *
     * @param antlrClasspath The Ant task implementation classpath. Must not be null.
     */
    public void setAntlrClasspath(FileCollection antlrClasspath) {
        this.antlrClasspath = antlrClasspath;
    }

    @Inject
    protected WorkerProcessFactory getWorkerProcessBuilderFactory() {
        throw new UnsupportedOperationException();
    }

    @TaskAction
    public void execute(InputChanges inputs) throws IOException {
        final Set<File> grammarFiles = new HashSet<File>();
        final AtomicBoolean cleanRebuild = new AtomicBoolean();
        inputs.getFileChanges(source).forEach(change -> {
            if (change.getFileType() == FileType.DIRECTORY) return;

            if (change.getChangeType() == ChangeType.REMOVED) {
                cleanRebuild.set(true);
                return;
            }

            File targetFile = change.getFile();
            grammarFiles.add(targetFile);
        });

        if (cleanRebuild.get()) {
            try (Stream<Path> pathStream = Files.walk(outputDirectory.toPath())) {
                pathStream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
            grammarFiles.addAll(source.getFiles());
        }

        AntlrWorkerManager manager = new AntlrWorkerManager();
        LOGGER.debug("AntlrWorkerManager created");
        AntlrSpec spec = new AntlrSpecFactory().create(this, grammarFiles, allSourceFiles.getFrom());
        LOGGER.debug("AntlrSpec created");
        AntlrResult result = manager.runWorker(getProject().getProjectDir(), getWorkerProcessBuilderFactory(), getAntlrClasspath(), spec);
        LOGGER.debug("AntlrResult obtained");
        evaluate(result);
    }

    private void evaluate(AntlrResult result) {
        int errorCount = result.getErrorCount();
        if (errorCount < 0) {
            throw new AntlrSourceGenerationException("There were errors during grammar generation", result.getException());
        } else if (errorCount == 1) {
            throw new AntlrSourceGenerationException("There was 1 error during grammar generation", result.getException());
        } else if (errorCount > 1) {
            throw new AntlrSourceGenerationException("There were "
                    + errorCount
                    + " errors during grammar generation", result.getException());
        }
    }

    @Override
    public Set<String> getIncludes() {
        return patternFilterable.getIncludes();
    }

    @Override
    public Set<String> getExcludes() {
        return patternFilterable.getExcludes();
    }

    @Override
    public PatternFilterable setIncludes(Iterable<String> includes) {
        return patternFilterable.setIncludes(includes);
    }

    @Override
    public PatternFilterable setExcludes(Iterable<String> excludes) {
        return patternFilterable.setExcludes(excludes);
    }

    @Override
    public PatternFilterable include(String... includes) {
        return patternFilterable.include(includes);
    }

    @Override
    public PatternFilterable include(Iterable<String> includes) {
        return patternFilterable.include(includes);
    }

    @Override
    public PatternFilterable include(Spec<FileTreeElement> includeSpec) {
        return patternFilterable.include(includeSpec);
    }

    @Override
    public PatternFilterable include(Closure includeSpec) {
        return patternFilterable.include(includeSpec);
    }

    @Override
    public PatternFilterable exclude(String... excludes) {
        return patternFilterable.exclude(excludes);
    }

    @Override
    public PatternFilterable exclude(Iterable<String> excludes) {
        return patternFilterable.exclude(excludes);
    }

    @Override
    public PatternFilterable exclude(Spec<FileTreeElement> excludeSpec) {
        return patternFilterable.exclude(excludeSpec);
    }

    @Override
    public PatternFilterable exclude(Closure excludeSpec) {
        return patternFilterable.exclude(excludeSpec);
    }
}
