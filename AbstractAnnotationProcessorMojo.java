package net.courtanet.maven;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;

public abstract class AbstractAnnotationProcessorMojo extends AbstractMojo {

    /**
     * @parameter expression = "${project}"
     * @readonly
     * @required
     */
    protected MavenProject project;

    /**
     * @parameter expression = "${project.testArtifacts}"
     * @readonly
     */
    private java.util.List<Artifact> pluginArtifacts;

    /**
     * Specify the directory where to place generated source files (same behaviour of -s option)
     * 
     * @parameter
     */
    private File outputDirectory;

    /**
     * Annotation Processor FQN (Full Qualified Name) - when processors are not specified, the default discovery
     * mechanism will be used
     * 
     * @parameter
     */
    private String[] processors;

    /**
     * Additional compiler arguments
     * 
     * @parameter
     */
    private String compilerArguments;

    /**
     * Controls whether or not the output directory is added to compilation
     * 
     * @parameter
     */
    private Boolean addOutputDirectoryToCompilationSources = true;

    /**
     * Indicates whether the build will continue even if there are compilation errors; defaults to true.
     * 
     * @parameter expression = "${annotation.failOnError}" default-value="true"
     * @required
     */
    private Boolean failOnError = true;

    /**
     * Indicates whether the compiler output should be visible, defaults to true.
     * 
     * @parameter expression = "${annotation.outputDiagnostics}" default-value="true"
     * @required
     */
    private boolean outputDiagnostics = true;

    /**
     * System properties set before processor invocation."
     * 
     * @parameter
     */

    @SuppressWarnings("rawtypes")
    private java.util.Map systemProperties;

    /**
     * includes pattern
     * 
     * @parameter
     */
    private String[] includes;

    /**
     * excludes pattern
     * 
     * @parameter
     */
    private String[] excludes;

    /**
     * @parameter expression = "${project.compileClasspathElements}"
     * @required
     * @readonly
     */
    private List<String> sourcepathElements;

    /**
     * Lock for parallel build support in maven See:
     * http://code.google.com/p/maven-annotation-plugin/issues/detail?id=32
     **/
    private static final ReentrantLock lock = new ReentrantLock();

    protected abstract File getSourceDirectory();

    private final List<String> scanSourcePath(List<String> sourcepathElements) throws IOException,
                    URISyntaxException {
        final ArrayList<String> files = new ArrayList<String>();
        for (String sourcePathEltement : sourcepathElements) {
            getLog().info("New sourcepath element [" +sourcePathEltement + "]");
          
            if (!sourcePathEltement.endsWith(".jar"))
                continue;
            final JarFile jarFile = new JarFile(new File(sourcePathEltement));
            final Enumeration<JarEntry> it = jarFile.entries();
            while (it.hasMoreElements()) {
                final JarEntry entry = it.nextElement();
                getLog().info("* Scan entry [" +entry.getName() + "]");
                if (!entry.getName().endsWith(".java"))
                    continue;
                final String fqcnSrc = entry.getName().replace('/', '.').substring(0, entry.getName().length() - 5);
                files.add(fqcnSrc);
            }
        }
        return files;
    }

    protected abstract File getOutputClassDirectory();

    private String buildProcessorsList() {
        if (processors == null || processors.length == 0)
            return null;
        final StringBuilder result = new StringBuilder();
        int i = 0;
        for (i = 0; i < processors.length - 1; ++i) {
            result.append(processors[i]).append(',');
        }
        result.append(processors[i]);
        return result.toString();
    }

    protected abstract Set<String> getClasspathElements(Set<String> result);

    private String buildCompileClasspath() {
        final Set<String> pathElements = new LinkedHashSet<String>();

        if (pluginArtifacts != null) {
            for (Artifact a : pluginArtifacts) {
                final File f = a.getFile();
                if (f != null) {
                    pathElements.add(a.getFile().getAbsolutePath());
                    getLog().debug("New classpath from [" + a.getScope() + "] : " + a.getFile().getAbsolutePath());
                }
            }
        }

        getClasspathElements(pathElements);
        final StringBuilder result = new StringBuilder();
        for (String elem : pathElements) {
            result.append(elem).append(File.pathSeparator);
        }
        return result.toString();
    }

    private String buildSourcePath() {
        final StringBuilder result = new StringBuilder();
        for (Object elem : sourcepathElements) {
            result.append(elem.toString()).append(File.pathSeparator);
        }
        return result.toString();
    }

    public void execute() throws MojoExecutionException {
        if ("pom".equalsIgnoreCase(project.getPackaging())) {
            getLog().warn("APT : No processing on pom packaging");
            return;
        }
        try {
            executeWithExceptionsHandled();
        } catch (Exception e1) {
            getLog().error("APT : Error during execution " + e1.getMessage());
            if (failOnError) {
                throw new MojoExecutionException("Error executing", e1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void executeWithExceptionsHandled() throws Exception {
        if (outputDirectory == null) {
            outputDirectory = getDefaultOutputDirectory();
        }

        ensureOutputDirectoryExists();
        addOutputToSourcesIfNeeded();

        lock.lock();
        try {
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            final StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
            final File sourceDir = getSourceDirectory();
            if (sourceDir == null) {
                getLog().warn("source directory cannot be read (null returned)! Processor task will be skipped");
                return;
            }
            if (!sourceDir.exists()) {
                getLog().warn("source directory doesn't exist! Processor task will be skipped");
                return;
            }
            if (!sourceDir.isDirectory()) {
                getLog().warn("source directory is invalid! Processor task will be skipped");
                return;
            }

            final String includesString = (includes == null || includes.length == 0) ? "**/*.java" : StringUtils.join(includes, ",");
            final String excludesString = (excludes == null || excludes.length == 0) ? null : StringUtils.join(excludes, ",");
            
            final List<JavaFileObject> compilationUnits = new ArrayList<JavaFileObject>();

            final List<File> files = FileUtils.getFiles(getSourceDirectory(), includesString, excludesString);
            if (files != null && !files.isEmpty()) {
                for (JavaFileObject unit : fileManager.getJavaFileObjectsFromFiles(files))
                    compilationUnits.add(unit);
            } else {
                getLog().warn("no source file(s) detected! Processor task will be skipped");
                return;
            }

            // -------------------------
            // ca marche pas par ici ...
            // -------------------------
            
            //if (1 == 0) {
                final List<String> sourceFqcns = scanSourcePath(sourcepathElements);
                if (sourceFqcns != null && !sourceFqcns.isEmpty()) {
                    for (String className : sourceFqcns) {
                      getLog().warn(className);
                    }
                } else {
                    getLog().warn("no source file(s) detected! Processor task will be skipped");
                    return;
                }

                for (JavaFileObject unit : compilationUnits) {
                    if (unit.getKind() != Kind.SOURCE)
                        System.out.println(unit.getName() + '\t' + unit.getKind());
                }
            //}

            final String compileClassPath = buildCompileClasspath();
            final String processorsList = buildProcessorsList();
            final List<String> options = new ArrayList<String>();

            options.add("-cp");
            options.add(compileClassPath);
            options.add("-sourcepath");
            options.add(buildSourcePath());
            options.add("-proc:only");

            addCompilerArguments(options);

            if (processorsList != null) {
                options.add("-processor");
                options.add(processorsList);
            } else {
                getLog().info("APT: No processors specified. Using default discovery mechanism.");
            }
            options.add("-d");
            options.add(getOutputClassDirectory().getPath());

            options.add("-s");
            options.add(outputDirectory.getPath());

            for (String option : options) {
                getLog().debug("javac option: " + option);
            }

            DiagnosticListener<JavaFileObject> dl = null;
            if (outputDiagnostics) {
                dl = new DiagnosticListener<JavaFileObject>() {
                    public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                        getLog().warn("APT : " + diagnostic);
                    }
                };
            } else {
                dl = new DiagnosticListener<JavaFileObject>() {
                    public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                    }
                };
            }

            if (systemProperties != null) {
                final Set<Map.Entry<String, String>> pSet = systemProperties.entrySet();
                for (Map.Entry<String, String> e : pSet) {
                    getLog().info(String.format("APT Forcing system property : [%s] = [%s]", e.getKey(), e.getValue()));
                    System.setProperty(e.getKey(), e.getValue());
                }
            }

            final CompilationTask task1 = compiler.getTask(new PrintWriter(System.out), fileManager, dl, options, null,
                            compilationUnits);
            if (!task1.call())
                throw new Exception("APT Error during processing.");
        } finally {
            lock.unlock();
        }
    }

    private static final List<File> mapFile(List<String> pathsAsString) {
        final List<File> files = new ArrayList<File>(pathsAsString.size());
        for (String path : pathsAsString)
            files.add(new File(path));
        return files;
    }

    private void addCompilerArguments(List<String> options) {
        if (!StringUtils.isEmpty(compilerArguments)) {
            for (String arg : compilerArguments.split(" ")) {
                if (!StringUtils.isEmpty(arg)) {
                    arg = arg.trim();
                    getLog().debug("APT: Adding compiler arg: " + arg);
                    options.add(arg);
                }
            }
        }
    }

    private void addOutputToSourcesIfNeeded() {
        final Boolean add = addOutputDirectoryToCompilationSources;
        if (add == null || add.booleanValue()) {
            getLog().info("APT: Source directory: " + outputDirectory + " added as source root");
            addCompileSourceRoot(project, outputDirectory.getAbsolutePath());
        }
    }

    protected abstract void addCompileSourceRoot(MavenProject project, String dir);

    public abstract File getDefaultOutputDirectory();

    private void ensureOutputDirectoryExists() {
        final File f = outputDirectory;
        if (!f.exists()) {
            f.mkdirs();
        }
        if (!getOutputClassDirectory().exists()) {
            getOutputClassDirectory().mkdirs();
        }
    }

}
