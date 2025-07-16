# CodebaseSlicer

A command-line utility built in Java to intelligently "slice" a Java codebase. It starts from a given root class and traverses its dependencies to generate a single, focused text file containing all relevant source code.

This tool is designed to solve the problem of analyzing or discussing large, complex codebases by creating a small, highly relevant context, perfect for providing to Large Language Models (LLMs) or for focused code review.

## Why Use CodebaseSlicer?

-   **Context over Quantity:** Instead of providing an entire 500,000+ token codebase for analysis, you can provide a targeted 10,000-token slice that is 100% relevant to the task at hand.
-   **Mimics Human Analysis:** The tool works like a developer tracing a feature—starting at an entry point (like a Controller or a Test) and following the trail of dependencies.
-   **Higher Quality Analysis:** By eliminating noise and providing a focused context, you can get significantly more accurate and helpful responses from AI assistants.
-   **Simple and Fast:** It's a single, self-contained JAR that runs quickly from the command line.

## Features

-   **Dependency Traversal:** Uses the powerful [JavaParser](https://javaparser.org/) library to build an Abstract Syntax Tree (AST) and accurately resolve type dependencies.
-   **Configurable Depth:** Control how "deep" the dependency search goes from the root class.
-   **Multi-Source Directory Support:** Correctly resolves dependencies across multiple source roots, like `src/main/java` and `src/test/java`.
-   **Java Version Aware:** Can be configured to correctly parse modern Java syntax (e.g., JDK 17, 21).
-   **Single-File Output:** Concatenates all relevant files into one easy-to-use `.txt` file, with clear delimiters.

## Download

You can download the latest pre-compiled executable JAR from the [**GitHub Releases page**](https://github.com/mariokoehler/codebase-slicer/releases).

## Prerequisites

To build and run this tool, you will need:

-   Java Development Kit (JDK 11 or newer)
-   [Apache Maven](https://maven.apache.org/download.cgi)

## Build Instructions

If you prefer to build the project locally:
1.  **Clone the repository:**
    ```bash
    git clone https://github.com/YourUsername/codebase-slicer.git
    cd codebase-slicer
    ```

2.  **Build the executable JAR using Maven:**
    ```bash
    mvn clean package
    ```

3.  After a successful build, the executable JAR will be located in the `target/` directory:
    `target/codebase-slicer-1.0.0-jar-with-dependencies.jar`

## Usage

Run the tool from the command line, providing the required arguments. It's best to run it from the root directory of the Java project you wish to analyze.

### Command Structure

```bash
java -jar /path/to/codebase-slicer-1.0.0-jar-with-dependencies.jar [ARGUMENTS]
```

### Arguments

| Argument  | Description                                                            | Required | Example                                    |
|-----------|------------------------------------------------------------------------|----------|--------------------------------------------|
| `-root`   | The fully qualified name of the class to start the analysis from.      | Yes      | `com.myproject.api.OrderController`        |
| `-source` | A comma-separated list of source directories to search for classes.    | Yes      | `src/main/java,src/test/java`              |
| `-output` | The filename for the final output file.                                | Yes      | `my_slice.txt`                             |
| `-depth`  | The maximum depth of dependency traversal (0 = only the root class).   | Yes      | `2`                                        |
| `-java`   | (Optional) The Java language level of the target project. Defaults to `LATEST`. | No       | `21`                                       |
| `-include`| (Optional) Comma-separated list of classes to include directly (no recursion). | No       | `com.myconfig.Constants,com.myutil.MyFactory` |

---

### Examples

**Note on line continuation:**
-   On Windows Command Prompt, use `^`.
-   On PowerShell or Linux/macOS, use `` ` ``.

#### Example 1: Slicing a Service Class

Let's create a slice starting from an `OrderService`, going 3 levels deep, for a project using Java 17.

```powershell
# In PowerShell
java -jar C:\path\to\codebase-slicer\target\codebase-slicer-1.0.0-jar-with-dependencies.jar `
     -root com.myproject.services.OrderService `
     -source src/main/java `
     -output order_service_slice.txt `
     -depth 3 `
     -java 17
```

This will produce `order_service_slice.txt` containing the `OrderService` class, its direct dependencies (like `OrderRepository`, `Order` entity, DTOs) at depth 1, their dependencies at depth 2, and so on.

#### Example 2: Slicing from a Unit Test

This demonstrates the power of using multiple source directories. We start from a test class, and the tool will correctly pull in both the test class and the application classes it depends on.

```powershell
# In PowerShell
java -jar C:\path\to\codebase-slicer\target\codebase-slicer-1.0.0-jar-with-dependencies.jar `
     -root com.myproject.services.OrderServiceTest `
     -source src/main/java,src/test/java `
     -output order_test_slice.txt `
     -depth 2 `
     -java 21
```

This is extremely useful for debugging a specific test or feature, as it gathers the test code, the code under test, and all relevant mocks and data structures into a single file.