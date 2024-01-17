package com.myorg;

import software.amazon.awscdk.App;

public final class JbsCdkApp {
    public static void main(final String[] args) {
        App app = new App();

        new JbsCdkStack(app, "JbsCdkStack");

        app.synth();
    }
}
