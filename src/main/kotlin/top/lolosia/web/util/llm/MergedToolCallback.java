package top.lolosia.web.util.llm;

import top.lolosia.web.util.event.Event;
import top.lolosia.web.util.event.EventHandle;
import top.lolosia.web.util.event.IEventHandle;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

public class MergedToolCallback implements ToolCallback {
    @NotNull ToolCallback callback;

    EventHandle<EntryEvent> entryEventHandle = new EventHandle<>();
    EventHandle<ExitEvent> exitEventHandle = new EventHandle<>();

    public MergedToolCallback(@NotNull ToolCallback callback) {
        this.callback = callback;
    }

    @NotNull
    @Override
    public ToolDefinition getToolDefinition() {
        return callback.getToolDefinition();
    }

    @NotNull
    @Override
    public ToolMetadata getToolMetadata() {
        return callback.getToolMetadata();
    }

    @NotNull
    @Override
    public String call(@Nullable String toolInput) {
        if (toolInput == null) {
            toolInput = "{}";
        }

        EntryEvent event = new EntryEvent(this, toolInput, null);
        entryEventHandle.publish(event);
        if (event.getThrowable() != null) {
            PackageKt.javaThrow(event.getThrowable());
        }

        if (event.getCancelReason() != null) {
            return event.getCancelReason();
        }

        @NotNull String finalToolInput = toolInput;
        String result = callOtherDispatcher(() -> callback.call(finalToolInput));
        exitEventHandle.publish(new ExitEvent(this, toolInput, null, result));
        return result;
    }

    @NotNull
    @Override
    public String call(@Nullable String toolInput, ToolContext tooContext) {
        if (toolInput == null) {
            toolInput = "{}";
        }
        EntryEvent entryEvent = new EntryEvent(this, toolInput, tooContext);
        entryEventHandle.publish(entryEvent);

        if (entryEvent.getThrowable() != null) {
            PackageKt.javaThrow(entryEvent.getThrowable());
        }

        if (entryEvent.getCancelReason() != null) {
            return entryEvent.getCancelReason();
        }

        @NotNull String finalToolInput = toolInput;
        String result = callOtherDispatcher(() -> callback.call(finalToolInput, tooContext));
        ExitEvent exitEvent = new ExitEvent(this, toolInput, tooContext, result);
        exitEventHandle.publish(exitEvent);

        if (exitEvent.getThrowable() != null) {
            PackageKt.javaThrow(exitEvent.getThrowable());
        }

        if (exitEvent.getCancelReason() != null) {
            return exitEvent.getCancelReason();
        }

        return result;
    }

    private <T> T callOtherDispatcher(Function0<T> callback0) {
        // if (callback instanceof AsyncMcpToolCallback) {
        //     try {
        //         return BuildersKt.runBlocking(
        //                 Dispatchers.getIO(),
        //                 (cs, co) -> callback0.invoke()
        //         );
        //     } catch (InterruptedException e) {
        //         throw new RuntimeException(e);
        //     }
        // } else
        return callback0.invoke();
    }

    public @NotNull IEventHandle<EntryEvent> getEntryEventHandle() {
        return entryEventHandle;
    }

    public @NotNull IEventHandle<ExitEvent> getExitEventHandle() {
        return exitEventHandle;
    }

    public static class EntryEvent extends Event {
        private final @NotNull String toolInput;
        private final @Nullable ToolContext toolContext;
        private @Nullable String cancelReason;
        private @Nullable Throwable throwable;

        public EntryEvent(@NotNull Object source, @NotNull String toolInput, @Nullable ToolContext toolContext) {
            super(source);
            this.toolInput = toolInput;
            this.toolContext = toolContext;
        }

        public @NotNull String getToolInput() {
            return toolInput;
        }

        public @Nullable ToolContext getToolContext() {
            return toolContext;
        }

        public @NotNull ToolCallback getTool() {
            return (ToolCallback) getSource();
        }

        public void cancel(String data) {
            this.cancelReason = data;
        }

        public @Nullable String getCancelReason() {
            return cancelReason;
        }

        /**
         * 抛出异常，中断调用
         * @param throwable 要抛出的异常
         */
        public void throwIt(Throwable throwable) {
            this.throwable = throwable;
        }

        /**
         * 获取需要抛出的异常
         * @return 抛出的异常，如果没有抛出异常则返回null
         */
        public @Nullable Throwable getThrowable() {
            return throwable;
        }
    }

    public static class ExitEvent extends EntryEvent {
        @NotNull String responseText;

        public ExitEvent(
                @NotNull Object source,
                @NotNull String toolInput,
                @Nullable ToolContext tooContext,
                @NotNull String responseText
        ) {
            super(source, toolInput, tooContext);
            this.responseText = responseText;
        }

        public @NotNull String getResponseText() {
            return responseText;
        }
    }
}
