package com.condense;

import picocli.AutoComplete.GenerateCompletion;
import picocli.CommandLine.Command;

@Command(name = "completion", description = "Generate shell completion script for condense")
public class CompletionCommand extends GenerateCompletion {
}
