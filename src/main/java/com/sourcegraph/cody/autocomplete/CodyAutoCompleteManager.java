package com.sourcegraph.cody.autocomplete;

import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.ImaginaryEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import com.sourcegraph.cody.CodyCompatibility;
import com.sourcegraph.cody.api.CompletionsService;
import com.sourcegraph.cody.autocomplete.prompt_library.*;
import com.sourcegraph.cody.autocomplete.render.*;
import com.sourcegraph.cody.vscode.*;
import com.sourcegraph.common.EditorUtils;
import com.sourcegraph.config.ConfigUtil;
import com.sourcegraph.config.NotificationActivity;
import com.sourcegraph.telemetry.GraphQlLogger;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Responsible for triggering and clearing inline code completions (the autocomplete feature). */
public class CodyAutoCompleteManager {
  private static final Logger logger = Logger.getInstance(CodyAutoCompleteManager.class);
  private static final Key<Boolean> KEY_EDITOR_SUPPORTED = Key.create("cody.editorSupported");
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  // TODO: figure out how to avoid the ugly nested `Future<CompletableFuture<T>>` type.
  private final AtomicReference<Optional<Future<CompletableFuture<Void>>>> currentJob =
      new AtomicReference<>(Optional.empty());
  private @Nullable AutocompleteTelemetry currentAutocompleteTelemetry = null;

  public static @NotNull CodyAutoCompleteManager getInstance() {
    return ApplicationManager.getApplication().getService(CodyAutoCompleteManager.class);
  }

  @RequiresEdt
  public void clearAutoCompleteSuggestions(@NotNull Editor editor) {
    // Log "suggested" event and clear current autocompletion
    Optional.ofNullable(editor.getProject())
        .ifPresent(
            p -> {
              if (currentAutocompleteTelemetry != null
                  && currentAutocompleteTelemetry.getStatus()
                      != AutocompletionStatus.TRIGGERED_NOT_DISPLAYED) {
                currentAutocompleteTelemetry.markCompletionHidden();
                GraphQlLogger.logAutocompleteSuggestedEvent(
                    p,
                    currentAutocompleteTelemetry.getLatencyMs(),
                    currentAutocompleteTelemetry.getDisplayDurationMs());
                currentAutocompleteTelemetry = null;
              }
            });

    // Cancel any running job
    cancelCurrentJob();

    // Clear any existing inline elements
    InlayModelUtils.getAllInlaysForEditor(editor).stream()
        .filter(inlay -> inlay.getRenderer() instanceof CodyAutoCompleteElementRenderer)
        .forEach(Disposer::dispose);
  }

  @RequiresEdt
  public boolean isEnabledForEditor(Editor editor) {
    return editor != null
        && isProjectAvailable(editor.getProject())
        && isEditorSupported(editor);
  }

  /**
   * Triggers auto-complete suggestions for the given editor at the specified offset.
   *
   * @param editor The editor instance to provide autocomplete for.
   * @param offset The character offset in the editor to trigger auto-complete at.
   */
  public void triggerAutoComplete(
      @NotNull Editor editor, int offset, InlineCompletionTriggerKind triggerKind) {
    if (!isEnabledForEditor(editor)) {
      if (triggerKind.equals(InlineCompletionTriggerKind.INVOKE)) {
        logger.warn("triggered autocomplete with invalid editor " + editor);
      }
      return;
    }

    final Project project = editor.getProject();
    if (project == null) {
      logger.warn("triggered autocomplete with null project");
      return;
    }
    currentAutocompleteTelemetry = AutocompleteTelemetry.createAndMarkTriggered();
    GraphQlLogger.logCodyEvent(project, "completion", "started");

    CancellationToken token = new CancellationToken();
    SourcegraphNodeCompletionsClient client =
        new SourcegraphNodeCompletionsClient(token);
    CodyAutoCompleteItemProvider provider =
        new CodyAutoCompleteItemProvider(
            new WebviewErrorMessenger(),
            client,
            new AutoCompleteDocumentProvider(),
            new History(),
            2048,
            4,
            200,
            0.6,
            0.1);

    // Gets AutoCompleteDocumentContext for the current offset
    TextDocument textDocument = new IntelliJTextDocument(editor, project);
    AutoCompleteDocumentContext autoCompleteDocumentContext =
        textDocument.getAutoCompleteContext(offset);

    // If the context has a valid completion trigger, cancel any running job
    // and asynchronously trigger the auto-complete
    if (triggerKind.equals(InlineCompletionTriggerKind.INVOKE)
        || autoCompleteDocumentContext.isCompletionTriggerValid()) { // TODO: skip this condition
      Callable<CompletableFuture<Void>> callable =
          () ->
              triggerAutoCompleteAsync(
                  project,
                  editor,
                  offset,
                  token,
                  provider,
                  textDocument,
                  autoCompleteDocumentContext,
                  triggerKind);
      // debouncing the autocomplete trigger
      cancelCurrentJob();
      this.currentJob.set(
          Optional.of(this.scheduler.schedule(callable, 20, TimeUnit.MILLISECONDS)));
    }
  }

  /** Asynchronously triggers auto-complete for the given editor and offset. */
  private CompletableFuture<Void> triggerAutoCompleteAsync(
      Project project,
      @NotNull Editor editor,
      int offset,
      @NotNull CancellationToken token,
      @NotNull CodyAutoCompleteItemProvider provider,
      @NotNull TextDocument textDocument,
      @NotNull AutoCompleteDocumentContext autoCompleteDocumentContext,
      InlineCompletionTriggerKind triggerKind) {
    return provider
        .provideInlineAutoCompleteItems(
            textDocument,
            textDocument.positionAt(offset),
            new InlineAutoCompleteContext(InlineAutoCompleteTriggerKind.Automatic, null),
            token)
        .thenAccept(
            result -> {
              if (Thread.interrupted()) {
                if (triggerKind.equals(InlineCompletionTriggerKind.INVOKE)) {
                  logger.warn("canceled autocomplete due to thread interruption");
                }
                return;
              }
              if (result.items.isEmpty()) {
                if (triggerKind.equals(InlineCompletionTriggerKind.INVOKE)) {
                  logger.warn("explicit autocomplete returned empty suggestions");
                  // NOTE(olafur): it would be nice to give the user a visual hint when this
                  // happens.
                  // We don't do anything now because it's unclear what would be the most idiomatic
                  // IntelliJ API to use.
                }
                return;
              }
              InlayModel inlayModel = editor.getInlayModel();
              // TODO: smarter logic around selecting the best completion item.
              Optional<InlineAutoCompleteItem> maybeItem =
                  result.items.stream()
                      .map(CodyAutoCompleteManager::removeUndesiredCharacters)
                      .map(item -> normalizeIndentation(item, EditorUtils.indentOptions(editor)))
                      .filter(resultItem -> !resultItem.insertText.isEmpty())
                      .findFirst();
              if (maybeItem.isEmpty()) {
                return;
              }
              InlineAutoCompleteItem item = maybeItem.get();
              try {
                ApplicationManager.getApplication()
                    .invokeLater(
                        () -> {
                          /* Clear existing completions */
                          this.clearAutoCompleteSuggestions(editor);

                          if (currentAutocompleteTelemetry != null) {
                            currentAutocompleteTelemetry.markCompletionDisplayed();
                          }

                          // Avoid displaying autocomplete when IntelliJ is already displaying
                          // built-in completions. When built-in completions are visible, we can't
                          // accept the Cody autocomplete with TAB because it accepts the built-in
                          // completion.
                          if (LookupManager.getInstance(project).getActiveLookup() != null) {
                            if (triggerKind.equals(InlineCompletionTriggerKind.INVOKE)) {
                              logger.warn(
                                  "Skipping autocomplete because lookup is active: " + item);
                            }
                            return;
                          }

                          displayAutocomplete(
                              editor, offset, autoCompleteDocumentContext, item, inlayModel);
                        });
              } catch (Exception e) {
                // TODO: do something smarter with unexpected errors.
                logger.warn(e);
              }
            });
  }

  private static void displayAutocomplete(
      @NotNull Editor editor,
      int offset,
      @NotNull AutoCompleteDocumentContext autoCompleteDocumentContext,
      InlineAutoCompleteItem item,
      InlayModel inlayModel) {
    AutoCompleteText autoCompleteText =
        item.toAutoCompleteText(autoCompleteDocumentContext.getSameLineSuffix().trim());
    autoCompleteText
        .getInlineRenderer(editor)
        .ifPresent(inlineRenderer -> inlayModel.addInlineElement(offset, true, inlineRenderer));
    autoCompleteText
        .getAfterLineEndRenderer(editor)
        .ifPresent(
            afterLineEndRenderer ->
                inlayModel.addAfterLineEndElement(offset, true, afterLineEndRenderer));
    autoCompleteText
        .getBlockRenderer(editor)
        .ifPresent(
            blockRenderer ->
                inlayModel.addBlockElement(offset, true, false, Integer.MAX_VALUE, blockRenderer));
  }

  // TODO: handle tabs in multiline autocomplete suggestions when we add them
  public static @NotNull InlineAutoCompleteItem normalizeIndentation(
      @NotNull InlineAutoCompleteItem item,
      @NotNull CommonCodeStyleSettings.IndentOptions indentOptions) {
    if (item.insertText.matches("^[\t ]*.+")) {
      String withoutLeadingWhitespace = item.insertText.stripLeading();
      String indentation =
          item.insertText.substring(
              0, item.insertText.length() - withoutLeadingWhitespace.length());
      String newIndentation = EditorUtils.tabsToSpaces(indentation, indentOptions);
      String newInsertText = newIndentation + withoutLeadingWhitespace;
      int rangeDiff = item.insertText.length() - newInsertText.length();
      Range newRange =
          item.range.withEnd(item.range.end.withCharacter(item.range.end.character - rangeDiff));
      return item.withInsertText(newInsertText).withRange(newRange);
    } else return item;
  }

  public static @NotNull InlineAutoCompleteItem removeUndesiredCharacters(
      @NotNull InlineAutoCompleteItem item) {
    // no zero-width spaces or line separator chars, pls
    String newInsertText = item.insertText.replaceAll("[\u200b\u2028]", "");
    int rangeDiff = item.insertText.length() - newInsertText.length();
    Range newRange =
        item.range.withEnd(item.range.end.withCharacter(item.range.end.character - rangeDiff));
    return item.withRange(newRange).withInsertText(newInsertText);
  }

  private boolean isProjectAvailable(Project project) {
    return project != null && !project.isDisposed();
  }

  private boolean isEditorSupported(@NotNull Editor editor) {
    if (editor.isDisposed()) {
      return false;
    }

    Boolean fromCache = KEY_EDITOR_SUPPORTED.get(editor);
    if (fromCache != null) {
      return fromCache;
    }

    boolean isSupported =
        isEditorInstanceSupported(editor)
            && CodyCompatibility.isSupportedProject(editor.getProject());
    KEY_EDITOR_SUPPORTED.set(editor, isSupported);
    return isSupported;
  }

  public static boolean isEditorInstanceSupported(@NotNull Editor editor) {
    return editor.getProject() != null
        && !editor.isViewer()
        && !editor.isOneLineMode()
        && !(editor instanceof EditorWindow)
        && !(editor instanceof ImaginaryEditor)
        && (!(editor instanceof EditorEx) || !((EditorEx) editor).isEmbeddedIntoDialogWrapper());
  }

  @Nullable
  private CompletionsService autoCompleteService(@NotNull Editor editor) {
    Optional<Project> project = Optional.ofNullable(editor.getProject());
    String instanceUrl =
        project
            .map(ConfigUtil::getSourcegraphUrl)
            .map(url -> url.endsWith("/") ? url : url + "/")
            .orElse(ConfigUtil.DOTCOM_URL);
    Optional<String> accessToken =
        project
            .flatMap(p -> Optional.ofNullable(ConfigUtil.getProjectAccessToken(p)))
            .filter(StringUtils::isNotEmpty);
    if (accessToken.isEmpty() && !ConfigUtil.isAccessTokenNotificationDismissed()) {
      NotificationActivity.notifyAboutSourcegraphAccessToken(Optional.of(instanceUrl));
    }
    return accessToken.map(token -> new CompletionsService(instanceUrl, token)).orElse(null);
  }

  private void cancelCurrentJob() {
    // TODO: change this implementation when we avoid nested `Future<CompletableFuture<T>>`
    this.currentJob
        .get()
        .ifPresent(
            job -> {
              if (job.isDone()) {
                try {
                  job.get().cancel(true);
                } catch (ExecutionException
                    | InterruptedException
                    | CancellationException ignored) {
                }
              } else {
                // Cancelling the toplevel `Future<>` appears to cancel the nested
                // `CompletableFuture<>`.
                // Feel free to reimplement this entire method if it's causing problems because this
                // logic is not bulletproof.
                job.cancel(true);
              }
            });
  }
}
