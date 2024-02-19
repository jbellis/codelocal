package com.sourcegraph.cody.autocomplete.prompt_library;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.safetensors.DType;
import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.sourcegraph.cody.api.CompletionsCallbacks;
import com.sourcegraph.cody.api.Message;
import com.sourcegraph.cody.vscode.CancellationToken;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SourcegraphNodeCompletionsClient {
  private static final Logger logger = Logger.getInstance(SourcegraphNodeCompletionsClient.class);
  private static volatile AbstractModel model;

  private final CancellationToken token;

  private static final AtomicBoolean modelLoading = new AtomicBoolean(false);

  /**
   * Singleton method for retrieving an instance of AbstractModel.
   * Ensures the model is loaded only once.
   *
   * @return the loaded model instance if available, null otherwise.
   */
  public static void maybeLoadModel() {
    // If the model is not loaded and no other thread is loading it, load it
    if (model == null && !modelLoading.get()) {
      if (modelLoading.compareAndSet(false, true)) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
          try {
            model = null;
//            model = AbstractModel.load(
//                    new File("/home/jonathan/Projects/Jlama/models/CodeLlama-7b-hf"),
//                    24, DType.F32, DType.I8);
          } finally {
            modelLoading.set(false); // Reset loading flag
          }
        });
      }
    }
  }

  public SourcegraphNodeCompletionsClient(CancellationToken token) {
    this.token = token;
  }

  public CompletableFuture<CompletionResponse> complete(CompletionParameters params) {
    CodeCompletionCallbacks cc = new CodeCompletionCallbacks(token);

    maybeLoadModel();
    if (model == null) {
      logger.info("Model still loading");
      // JTODO is this the right thing to do?
      cc.onComplete();
      return cc.promise;
    }

    StringBuilder buffer = new StringBuilder();
    var fullPrompt = params.messages.stream().map(Message::prompt).collect(Collectors.joining("\n"));
    logger.info("Full prompt is " + fullPrompt);
//    model.generate(fullPrompt, 0.2f, params.maxTokensToSample, true, (token, time) -> {
//      buffer.append(token);
//
//      if (token.equals("\n")) {
//        cc.onData(buffer.toString());
//        buffer.setLength(0);
//      }
//    });

    cc.onData(buffer.toString());
    cc.onComplete();

    return cc.promise;
  }

  private static class CodeCompletionCallbacks implements CompletionsCallbacks {
    private final CancellationToken token;
    CompletableFuture<CompletionResponse> promise = new CompletableFuture<>();
    List<String> chunks = new ArrayList<>();

    private CodeCompletionCallbacks(CancellationToken token) {
      this.token = token;
    }

    @Override
    public void onSubscribed() {
      // Do nothing
    }

    @Override
    public void onData(String data) {
      chunks.add(data);
    }

    @Override
    public void onError(Throwable error) {
      promise.complete(new CompletionResponse("", error.getMessage()));
      logger.warn(error);
    }

    @Override
    public void onComplete() {
      String json = String.join("", chunks);
      CompletionResponse completionResponse = new Gson().fromJson(json, CompletionResponse.class);
      promise.complete(completionResponse);
    }

    @Override
    public void onCancelled() {
      this.token.abort();
    }
  }
}
