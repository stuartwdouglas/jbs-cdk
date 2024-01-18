package com.myorg;

import software.amazon.awscdk.App;

public final class JbsDemoApp {
    public static void main(final String[] args) {
        App app = new App();

        new JbsDemoStack(app, "JbsDemoStack");

        app.synth();
    }
}
