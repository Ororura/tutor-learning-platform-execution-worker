package com.tutorplatform.worker.infrastructure.docker;

import com.tutorplatform.worker.application.ExecutionLanguage;
import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import java.util.List;
import java.util.Map;

final class LanguageRuntimes {
    record Runtime(String sourceFile, String image, boolean compilationRequired) {
        List<String> command(int memoryMb) {
            if (!compilationRequired) {
                return List.of("/usr/local/bin/python3", "-I", "-B", "/workspace/main.py");
            }
            return List.of("/opt/java/openjdk/bin/java", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
                "-XX:-UsePerfData", "-Xss256k", "-Xmx" + Math.max(16, memoryMb - 80) + "m",
                "-XX:MaxMetaspaceSize=32m", "-XX:ReservedCodeCacheSize=16m",
                "-cp", "/workspace/program.jar", "Main");
        }
    }

    static Map<ExecutionLanguage, Runtime> registry(ExecutionWorkerProperties.Runtime properties) {
        return Map.of(
            ExecutionLanguage.PYTHON, new Runtime("main.py", properties.pythonImage(), false),
            ExecutionLanguage.JAVA, new Runtime("Main.java", properties.javaImage(), true)
        );
    }
    private LanguageRuntimes() { }
}
