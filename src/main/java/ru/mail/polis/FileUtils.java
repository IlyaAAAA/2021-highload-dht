/*
 * Copyright 2020 (c) Odnoklassniki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.mail.polis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility methods for handling files.
 *
 * @author Vadim Tsesko
 */
public final class FileUtils {
    private static final String TEMP_PREFIX = "highload-dht";

    private static final Logger LOG = LoggerFactory.getLogger(FileUtils.class);

    private FileUtils() {
        // Don't instantiate
    }

    /**
     * Creates and returns a new unused yet temporary directory.
     */
    public static Path createTempDirectory() throws IOException {
        final Path data = Files.createTempDirectory(TEMP_PREFIX);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (Files.exists(data)) {
                    recursiveDelete(data);
                }
            } catch (IOException e) {
                LOG.error("Can't delete temporary directory: {}", data, e);
            }
        }));
        return data;
    }

    /**
     * Recursively removes path.
     */
    public static void recursiveDelete(final Path path) throws IOException {
        Files.walkFileTree(
                path,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(
                            final Path file,
                            final BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(
                            final Path dir,
                            final IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /**
     * Recursively calculates directory size.
     */
    public static long directorySize(final Path path) throws IOException {
        final AtomicLong result = new AtomicLong(0L);
        Files.walkFileTree(
                path,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(
                            final Path file,
                            final BasicFileAttributes attrs) {
                        result.addAndGet(attrs.size());
                        return FileVisitResult.CONTINUE;
                    }
                });
        return result.get();
    }

    public static Iterator<Path> getPathIterator(Path dir, String pathEnd) throws IOException {
        Iterator<Path> paths;
        try (Stream<Path> streamPaths = Files.walk(Paths.get(dir.toUri()))) {
            paths = streamPaths.filter(path -> path.toString().endsWith(pathEnd))
                    .collect(Collectors.toList())
                    .stream()
                    .sorted(Comparator.comparing(o -> getFileNumber(o, pathEnd)))
                    .collect(Collectors.toList())
                    .iterator();
        }
        return paths;
    }

    public static Integer getFileNumber(Path path, String endFile) {
        String stringPath = path.toString();

        int lastSlash = 0;

        for (int i = 0; i < stringPath.length(); i++) {
            if (stringPath.charAt(i) == File.separatorChar) {
                lastSlash = i;
            }
        }

        stringPath = stringPath.substring(lastSlash + 1);

        int firstNumberIndex = 0;

        for (int i = 0; i < stringPath.length(); i++) {
            if (Character.isDigit(stringPath.charAt(i))) {
                firstNumberIndex = i;
                break;
            }
        }

        return Integer.parseInt(stringPath.substring(firstNumberIndex, stringPath.length() - endFile.length()));
    }
}
