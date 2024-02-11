// SPDX-FileCopyrightText: © 2022-2024 Greg Christiana <maxuser@minescript.net>
// SPDX-License-Identifier: GPL-3.0-only

package net.minescript.common;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ScriptConfig {
  private static final Logger LOGGER = LogManager.getLogger();

  private final Path minescriptDir;
  private final ImmutableList<String> builtinCommands;
  private ImmutableList<Path> commandPath =
      ImmutableList.of(Paths.get("system", "exec"), Paths.get(""));
  private Map<String, FileTypeConfig> fileTypeMap = new ConcurrentHashMap<>();
  private List<String> fileExtensions = new ArrayList<>();

  public ScriptConfig(String minescriptDirName, ImmutableList<String> builtinCommands) {
    this.minescriptDir = Paths.get(System.getProperty("user.dir"), minescriptDirName);
    this.builtinCommands = builtinCommands;
  }

  public void setCommandPath(List<Path> commandPath) {
    this.commandPath = ImmutableList.copyOf(commandPath);
  }

  public ImmutableList<Path> commandPath() {
    return commandPath;
  }

  /**
   * Returns names of commands and directories accessible from command path that match the prefix.
   */
  public List<String> findCommandPrefixMatches(String prefix) throws IOException {
    if (prefix.isEmpty()) {
      return ImmutableList.of();
    }

    var matches = new ArrayList<String>();
    for (String builtin : builtinCommands) {
      if (builtin.startsWith(prefix)) {
        matches.add(builtin);
      }
    }
    Path prefixPath = Paths.get(prefix);
    commandDirLoop:
    for (Path commandDir : commandPath) {
      Path resolvedCommandDir = minescriptDir.resolve(commandDir);
      Path resolvedDir = resolvedCommandDir;
      // Iterate all but the last component of the prefix path to walk the path within commandDir.
      for (int i = 0; i < prefixPath.getNameCount() - 1; ++i) {
        resolvedDir = resolvedDir.resolve(prefixPath.getName(i));
        if (!Files.isDirectory(resolvedDir)) {
          continue commandDirLoop;
        }
      }
      String prefixFileName = prefixPath.getFileName().toString();
      Files.list(resolvedDir)
          .forEach(
              path -> {
                String fileName = path.getFileName().toString();
                if (Files.isDirectory(path)) {
                  if (fileName.startsWith(prefixFileName)) {
                    try {
                      matches.add(resolvedCommandDir.relativize(path).toString() + File.separator);
                    } catch (IllegalArgumentException e) {
                      LOGGER.info(
                          "Dir completion: resolvedCommandDir: {}  path: {}",
                          resolvedCommandDir,
                          path);
                      throw e;
                    }
                  }
                } else {
                  if (fileExtensions.contains(getFileExtension(fileName))) {
                    try {
                      String scriptName =
                          removeFileExtension(resolvedCommandDir.relativize(path).toString());
                      if (scriptName.startsWith(prefix) && !matches.contains(scriptName)) {
                        matches.add(scriptName);
                      }
                    } catch (IllegalArgumentException e) {
                      LOGGER.info(
                          "File completion: resolvedCommandDir: {}  path: {}",
                          resolvedCommandDir,
                          path);
                      throw e;
                    }
                  }
                }
              });
    }
    return matches;
  }

  public record CommandConfig(String extension, List<String> command, List<String> environment) {}

  public void configureFileType(CommandConfig commandConfig) {
    Preconditions.checkNotNull(commandConfig.extension);
    Preconditions.checkArgument(
        commandConfig.extension.startsWith("."),
        "File extension does not start with dot: \"%s\"",
        commandConfig.extension);
    Preconditions.checkArgument(
        !fileTypeMap.containsKey(commandConfig.extension),
        "File extension already configured: \"%s\"",
        commandConfig.extension);

    var fileTypeConfig =
        new FileTypeConfig(
            CommandBuilder.create(commandConfig.command),
            commandConfig.environment == null
                ? new String[0]
                : commandConfig.environment.stream()
                    .map(s -> s.replace("{minescript_dir}", minescriptDir.toString()))
                    .toArray(String[]::new));
    fileTypeMap.put(commandConfig.extension, fileTypeConfig);
    fileExtensions.add(commandConfig.extension);

    LOGGER.info(
        "Configured file extension `{}` for commands: {}", commandConfig.extension, fileTypeConfig);
  }

  public List<String> supportedFileExtensions() {
    return fileExtensions;
  }

  public Path resolveCommandPath(String command) {
    for (Path commandDir : commandPath) {
      for (String fileExtension : fileExtensions) {
        Path path = minescriptDir.resolve(commandDir).resolve(command + fileExtension);
        if (Files.exists(path)) {
          return path;
        }
      }
    }
    return null;
  }

  public ExecutableCommand getExecutableCommand(BoundCommand boundCommand) {
    var fileTypeConfig = fileTypeMap.get(boundCommand.fileExtension());
    if (fileTypeConfig == null) {
      return null;
    }
    return new ExecutableCommand(
        fileTypeConfig.commandBuilder.buildExecutableCommand(boundCommand),
        fileTypeConfig.environment());
  }

  /**
   * Returns file extension including the ".", e.g. ".py" for "foo.py", or "" if no extension found.
   */
  private static String getFileExtension(String fileName) {
    int lastIndex = fileName.lastIndexOf('.');
    if (lastIndex == -1) {
      return ""; // No file extension found
    }
    return fileName.substring(lastIndex);
  }

  private static String removeFileExtension(String fileName) {
    int lastIndex = fileName.lastIndexOf('.');
    if (lastIndex == -1) {
      return fileName;
    }
    return fileName.substring(0, lastIndex);
  }

  public record BoundCommand(Path scriptPath, String[] command) {
    String fileExtension() {
      if (scriptPath == null) {
        return null;
      }
      return getFileExtension(scriptPath.getFileName().toString());
    }
  }

  public record ExecutableCommand(String[] command, String[] environment) {}

  public record FileTypeConfig(CommandBuilder commandBuilder, String[] environment) {}

  /**
   * Command builder for translating Minescript commands into executable commands.
   *
   * @param pattern command pattern containing "{command}" and "{args}" placeholders
   * @param commandIndex index of "{command}" in pattern
   * @param argsIndex index of "{args}" in pattern
   */
  public record CommandBuilder(String[] pattern, int commandIndex, int argsIndex) {

    public static CommandBuilder create(List<String> commandPattern) {
      int commandIndex = commandPattern.indexOf("{command}");
      Preconditions.checkArgument(
          commandIndex >= 0, "{command} not found in pattern: %s", commandPattern);

      int argsIndex = commandPattern.indexOf("{args}");
      Preconditions.checkArgument(
          commandIndex >= 0, "{args} not found in pattern: %s", commandPattern);

      return new CommandBuilder(commandPattern.toArray(STRING_ARRAY), commandIndex, argsIndex);
    }

    public String[] buildExecutableCommand(BoundCommand boundCommand) {
      var executableCommand = new ArrayList<String>();
      String[] command = boundCommand.command();
      for (int i = 0; i < pattern.length; ++i) {
        if (i == commandIndex) {
          executableCommand.add(boundCommand.scriptPath().toString());
        } else if (i == argsIndex) {
          for (int j = 1; j < command.length; ++j) {
            executableCommand.add(command[j]);
          }
        } else {
          executableCommand.add(pattern[i]);
        }
      }
      return executableCommand.toArray(STRING_ARRAY);
    }
  }

  // Empty string array for use with List<String>.toArray(...).
  private static final String[] STRING_ARRAY = new String[0];
}