package de.mkoehler.codebaseslicer;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A command-line utility to create a "slice" of a Java codebase.
 * <p>
 * This tool performs a dependency analysis starting from a specified root class. It traverses the
 * class's dependencies (fields, method return types, parent classes, interfaces, etc.) up to a
 * specified depth using a breadth-first search (BFS) algorithm. The result is a single text file
 * containing the full source code of the root class and all its relevant dependencies.
 * </p>
 * <p>
 * This is particularly useful for creating a focused context for analysis or for providing a
 * concise, relevant code summary to Large Language Models (LLMs).
 * It uses the {@code com.github.javaparser} library to accurately parse Java source code and
 * resolve type symbols.
 * </p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * java -jar codebase-slicer.jar -root <...> -source <...> -output <...> -depth <...> [-java <...>] [-include <...>]
 * }</pre>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * java -jar codebase-slicer.jar ^
 *      -root com.myproject.services.OrderServiceTest ^
 *      -source src/main/java,src/test/java ^
 *      -output order_service_test_slice.txt ^
 *      -depth 2 ^
 *      -java 21 ^
 *      -include com.myproject.config.ImportantConstants,com.myproject.utils.ReflectionHelper
 * }</pre>
 */
public class CodebaseSlicer {

    // --- Static fields for managing the slicing process ---

    /** A list of all source root directories for the project (e.g., src/main/java, src/test/java). */
    private static List<Path> projectSourcePaths;
    /** The path to the output file where the summary will be saved. */
    private static Path outputPath;
    /** The maximum depth for the dependency traversal. 0 means only the root class. */
    private static int maxDepth;
    /** The fully qualified name of the class where the analysis starts. */
    private static String rootClassName;

    /** A set to track classes that have already been processed to avoid cycles and redundant work. */
    private static final Set<String> processedClasses = new HashSet<>();
    /** A queue for the breadth-first search, holding classes that need to be analyzed. */
    private static final Queue<WorkItem> workQueue = new LinkedList<>();
    /** The final set of files to be included in the output, mapping qualified name to file path. */
    private static final Map<String, Path> finalFileSet = new HashMap<>();

    /**
     * The main entry point for the application.
     * <p>
     * This method orchestrates the entire process:
     * <ol>
     *   <li>Parses command-line arguments.</li>
     *   <li>Configures the JavaParser instance with the correct language level and symbol solver.</li>
     *   <li>Initializes the breadth-first search by adding the root class to the work queue.</li>
     *   <li>Processes the queue until it's empty, finding dependencies for each class.</li>
     *   <li>Processes any additional classes specified with the -include flag.</li>
     *   <li>Writes the collected source files to the specified output file.</li>
     * </ol>
     *
     * @param args The command-line arguments provided to the program.
     * @throws IOException if there is an error reading source files or writing the output file.
     */
    public static void main(String[] args) throws IOException {
        Map<String, String> argMap = parseArgs(args);
        rootClassName = argMap.get("-root");
        String sourceDirsStr = argMap.get("-source");
        String outputFile = argMap.get("-output");
        String depthStr = argMap.get("-depth");
        String javaVersionStr = argMap.getOrDefault("-java", "LATEST");
        // --- NEW: Parse the -include argument ---
        String includeStr = argMap.get("-include");

        if (rootClassName == null || sourceDirsStr == null || outputFile == null || depthStr == null) {
            // --- MODIFIED: Updated usage string ---
            System.err.println("Usage: java -jar <jarfile> -root <com.example.MyClass> -source <path1,path2,...> -output <summary.txt> -depth <number> [-java <version>] [-include <class1,class2,...>]");
            System.err.println("Example: -root de.otto.payments.b2ccreditfraud.bonim.entities.repos.CustomerTypeRepository -source C:\\Users\\MKOEHLER\\intellij-workspace\\piranha_bonim\\src\\main\\java,C:\\Users\\MKOEHLER\\intellij-workspace\\piranha_bonim\\src\\test\\java -output customerTypeRepo_slice.txt -depth 2");
            return;
        }

        // --- NEW: Prepare list of explicitly included classes ---
        List<String> explicitlyIncludedClasses = Collections.emptyList();
        if (includeStr != null && !includeStr.trim().isEmpty()) {
            explicitlyIncludedClasses = Arrays.stream(includeStr.split(","))
                    .map(String::trim)
                    .collect(Collectors.toList());
        }

        projectSourcePaths = Arrays.stream(sourceDirsStr.split(","))
                .map(String::trim)
                .map(Paths::get)
                .collect(Collectors.toList());

        outputPath = Paths.get(outputFile);
        maxDepth = Integer.parseInt(depthStr);

        try {
            ParserConfiguration.LanguageLevel languageLevel = "LATEST".equalsIgnoreCase(javaVersionStr)
                    ? ParserConfiguration.LanguageLevel.JAVA_21 // Defaulting to a recent version if 'LATEST' is specified
                    : ParserConfiguration.LanguageLevel.valueOf("JAVA_" + javaVersionStr);
            System.out.println("Setting JavaParser language level to: " + languageLevel);
            StaticJavaParser.getConfiguration().setLanguageLevel(languageLevel);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: Invalid Java version specified with -java flag: " + javaVersionStr);
            return;
        }

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver()); // For JDK classes
        for (Path sourcePath : projectSourcePaths) {
            System.out.println("Adding source directory to solver: " + sourcePath);
            typeSolver.add(new JavaParserTypeSolver(sourcePath.toFile()));
        }
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);

        System.out.println("\nStarting analysis...");
        addWork(rootClassName, 0);

        while (!workQueue.isEmpty()) {
            WorkItem item = workQueue.poll();
            if (item.depth > maxDepth || processedClasses.contains(item.qualifiedName)) continue;

            System.out.printf("Processing: %s (Depth: %d)%n", item.qualifiedName, item.depth);
            processedClasses.add(item.qualifiedName);

            try {
                findDependencies(item);
            } catch (Exception e) {
                System.err.println("Could not resolve or parse: " + item.qualifiedName + ". Skipping. Error: " + e.getMessage());
            }
        }

        // --- NEW: Process the explicitly included classes without recursion ---
        if (!explicitlyIncludedClasses.isEmpty()) {
            System.out.println("\nProcessing explicitly included classes...");
            for (String className : explicitlyIncludedClasses) {
                // Check if it was already found by the dependency traversal
                if (finalFileSet.containsKey(className)) {
                    System.out.println("  -> " + className + " was already included by the dependency tree. Skipping.");
                    continue;
                }
                Path filePath = convertQualifiedNameToPath(className);
                if (filePath != null) {
                    System.out.println("  -> Adding: " + className);
                    finalFileSet.put(className, filePath);
                } else {
                    System.err.println("  -> Could not find source file for explicitly included class: " + className);
                }
            }
        }

        writeOutput();
        System.out.println("\nProcessing complete. Summary saved to " + outputPath);
    }

    /**
     * Traverses a parsed Java file to find all referenced types and adds them to the work queue.
     * <p>
     * This is the core analysis step for a single file. It resolves all type references within the file,
     * including extended classes, implemented interfaces, field types, and method signatures.
     * Any new, relevant types are added to the work queue for subsequent processing.
     * </p>
     *
     * @param item The {@link WorkItem} representing the class to analyze.
     * @throws IOException if the source file cannot be read or parsed.
     */
    private static void findDependencies(WorkItem item) throws IOException {
        Path filePath = convertQualifiedNameToPath(item.qualifiedName);
        if (filePath == null) {
            System.err.println("  -> Could not find source file for: " + item.qualifiedName);
            return;
        }

        finalFileSet.put(item.qualifiedName, filePath);
        CompilationUnit cu = StaticJavaParser.parse(filePath);
        Set<String> referencedTypes = new HashSet<>();

        // Find all direct class references (fields, variables, method calls, etc.)
        cu.findAll(ClassOrInterfaceType.class).forEach(type -> {
            try {
                ResolvedType resolvedType = type.resolve();
                if (resolvedType.isReferenceType()) {
                    referencedTypes.add(resolvedType.asReferenceType().getQualifiedName());
                }
            } catch (Exception e) { /* Ignore unsolvable types */ }
        });

        // Find parent classes and implemented interfaces
        cu.findAll(TypeDeclaration.class).forEach(type -> {
            if (type.isClassOrInterfaceDeclaration()) {
                type.asClassOrInterfaceDeclaration().getExtendedTypes().forEach(parent -> {
                    try {
                        ResolvedType resolvedType = parent.resolve();
                        if (resolvedType.isReferenceType()) {
                            referencedTypes.add(resolvedType.asReferenceType().getQualifiedName());
                        }
                    } catch (Exception e) { /* Ignore */ }
                });
                type.asClassOrInterfaceDeclaration().getImplementedTypes().forEach(iface -> {
                    try {
                        ResolvedType resolvedType = iface.resolve();
                        if (resolvedType.isReferenceType()) {
                            referencedTypes.add(resolvedType.asReferenceType().getQualifiedName());
                        }
                    } catch (Exception e) { /* Ignore */ }
                });
            }
        });

        // Add discovered types to the work queue
        for (String type : referencedTypes) {
            // Filter out JDK classes
            if (!type.startsWith("java.") && !type.startsWith("javax.")) {
                addWork(type, item.depth + 1);
            }
        }
    }

    /**
     * Adds a new work item to the processing queue if it meets the criteria.
     * <p>
     * A work item is added only if it has not already been processed, is not already in the queue,
     * and its depth does not exceed the maximum allowed depth.
     * </p>
     *
     * @param qualifiedName The fully qualified name of the class to potentially add.
     * @param depth         The dependency depth of this class relative to the root.
     */
    private static void addWork(String qualifiedName, int depth) {
        if (!processedClasses.contains(qualifiedName) && depth <= maxDepth) {
            if (workQueue.stream().noneMatch(w -> w.qualifiedName.equals(qualifiedName))) {
                workQueue.add(new WorkItem(qualifiedName, depth));
            }
        }
    }

    /**
     * Converts a fully qualified Java class name to its corresponding file system path.
     * <p>
     * It handles inner classes by looking for the outer class's file. It searches through all
     * configured source directories to locate the file.
     * </p>
     *
     * @param qualifiedName The fully qualified name of the class (e.g., "com.example.MyClass").
     * @return A {@link Path} to the corresponding .java file, or {@code null} if not found in any source directory.
     */
    private static Path convertQualifiedNameToPath(String qualifiedName) {
        String mainClassName = qualifiedName.split("\\$")[0];
        String relativePath = mainClassName.replace('.', File.separatorChar) + ".java";

        for (Path sourceRoot : projectSourcePaths) {
            Path candidate = sourceRoot.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Gathers the source code from all collected files and writes them to the final output file.
     * <p>
     * The files are sorted alphabetically for a consistent and predictable output. Each file's
     * content is enclosed in a 'START FILE' and 'END FILE' block for easy parsing.
     * </p>
     *
     * @throws IOException If an error occurs while writing to the output file.
     */
    private static void writeOutput() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("### Codebase Slice starting from root: %s (Depth: %d) ###%n%n", rootClassName, maxDepth));

        List<Path> sortedFiles = finalFileSet.values().stream().sorted().collect(Collectors.toList());

        for (Path filePath : sortedFiles) {
            String relativePath = filePath.toString(); // Fallback to absolute path
            for (Path sourceRoot : projectSourcePaths) {
                if (filePath.toAbsolutePath().startsWith(sourceRoot.toAbsolutePath())) {
                    relativePath = sourceRoot.relativize(filePath).toString().replace('\\', '/');
                    break;
                }
            }
            sb.append("--- START FILE: ").append(relativePath).append(" ---\n");
            sb.append(Files.readString(filePath));
            sb.append("\n--- END FILE: ").append(relativePath).append(" ---\n\n");
        }
        Files.writeString(outputPath, sb.toString());
    }

    /**
     * A simple parser for command-line arguments.
     * <p>
     * It assumes arguments are provided in key-value pairs (e.g., {@code -key value}).
     * </p>
     *
     * @param args The array of command-line arguments from {@code main}.
     * @return A {@link Map} where keys are the flags (e.g., "-root") and values are the subsequent strings.
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 < args.length) {
                map.put(args[i], args[i + 1]);
            }
        }
        return map;
    }

    /**
     * A simple data structure to hold information about a class to be processed.
     * <p>
     * This is used by the breadth-first search queue to track not only the class to process
     * but also its depth in the dependency graph relative to the root class.
     * </p>
     */
    static class WorkItem {
        /** The fully qualified name of the class. */
        String qualifiedName;
        /** The dependency depth of this class. The root is at depth 0. */
        int depth;

        /**
         * Constructs a new WorkItem.
         *
         * @param qualifiedName The fully qualified name of the class.
         * @param depth The dependency depth of the class.
         */
        WorkItem(String qualifiedName, int depth) {
            this.qualifiedName = qualifiedName;
            this.depth = depth;
        }
    }
}