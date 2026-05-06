package com.huangyifei.rag.service;

import reactor.core.Disposable;

public class ChatStreamHandle {

    private final Disposable subscription;
    private final Runnable onCancel;

    public ChatStreamHandle(Disposable subscription, Runnable onCancel) {
        this.subscription = subscription;
        this.onCancel = onCancel;
    }

    public void cancel() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        if (onCancel != null) {
            onCancel.run();
        }
    }
}
