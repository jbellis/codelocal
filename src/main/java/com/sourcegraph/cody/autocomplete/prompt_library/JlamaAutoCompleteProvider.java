package com.sourcegraph.cody.autocomplete.prompt_library;

import com.github.tjake.jlama.safetensors.DType;
import com.intellij.openapi.diagnostic.Logger;
import com.sourcegraph.cody.api.Message;
import com.sourcegraph.cody.api.Speaker;
import com.sourcegraph.cody.vscode.CancellationToken;
import com.sourcegraph.cody.vscode.Completion;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.github.tjake.jlama.model.AbstractModel;

import static com.sourcegraph.cody.autocomplete.prompt_library.TextProcessing.CLOSING_CODE_TAG;
import static com.sourcegraph.cody.autocomplete.prompt_library.TextProcessing.OPENING_CODE_TAG;
import static com.sourcegraph.cody.autocomplete.prompt_library.TextProcessing.PrefixComponents;
import static com.sourcegraph.cody.autocomplete.prompt_library.TextProcessing.getHeadAndTail;

public class JlamaAutoCompleteProvider extends AutoCompleteProvider {

  public static final Logger logger = Logger.getInstance(JlamaAutoCompleteProvider.class);
  private final AbstractModel model;

  public JlamaAutoCompleteProvider(
      SourcegraphNodeCompletionsClient completionsClient,
      int promptChars,
      int responseTokens,
      List<ReferenceSnippet> snippets,
      String prefix,
      String suffix,
      String injectPrefix,
      int defaultN)
  {
    super(completionsClient, promptChars, responseTokens, snippets, prefix, suffix, injectPrefix, defaultN);
    try {
      model = AbstractModel.load(new File("/home/jonathan/Projects/Jlama/models/CodeLlama-7b-hf"),
                                 24, DType.F32, DType.I8);
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected List<Message> createPromptPrefix() {
    String[] prefixLines = this.prefix.split("\n");
    if (prefixLines.length == 0) logger.warn("Jlama: missing prefix lines");

    PrefixComponents pc = getHeadAndTail(this.prefix);

    return Arrays.asList(
        new Message(
            Speaker.HUMAN,
            "You are Cody, a code completion AI developed by Sourcegraph. You write code in between tags like this:"
                + OPENING_CODE_TAG
                + "/* Code goes here */"
                + CLOSING_CODE_TAG),
        new Message(Speaker.ASSISTANT, "I am Cody, a code completion AI developed by Sourcegraph."),
        new Message(
            Speaker.HUMAN,
            "Complete this code: " + OPENING_CODE_TAG + pc.head.trimmed + CLOSING_CODE_TAG + "."),
        new Message(
            Speaker.ASSISTANT, "Okay, here is some code: " + OPENING_CODE_TAG + pc.tail.trimmed));
  }

  @Override
  public CompletableFuture<List<Completion>> generateCompletions(
      CancellationToken abortSignal, Optional<Integer> n) {
    String prefix = this.prefix + this.injectPrefix;

    // Create prompt
    List<Message> prompt = this.createPrompt();
    // FIXME this is comparing list length with string length
    if (prompt.size() > this.promptChars) {
      logger.warn("Cody: Anthropic: prompt length exceeded maximum allotted chars");
      return CompletableFuture.completedFuture(Collections.emptyList());
    }

    // Issue request
    int maxTokensToSample = Math.min(100, this.responseTokens);
    List<String> stopSequences = List.of(Speaker.HUMAN.prompt(), CLOSING_CODE_TAG, "\n\n");
    CompletableFuture<List<CompletionResponse>> promises =
        batchCompletions(
            this.completionsClient,
            new CompletionParameters()
                .withMessages(prompt)
                .withMaxTokensToSample(maxTokensToSample)
                .withStopSequences(stopSequences)
                .withTemperature(0.5f)
                .withTopK(-1)
                .withTopP(-1),
            n.orElseGet(() -> this.defaultN));

    // Post-process
    return promises.thenApply(
        responses ->
            responses.stream()
                .map(
                    resp ->
                        new Completion(
                            prefix,
                            prompt,
                            PostProcess.postProcess(this.prefix, resp.completion),
                            resp.stopReason))
                .collect(Collectors.toList()));
  }
}
