package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.Secret;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedTaskImageOptions;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.constructs.Construct;

import java.util.Map;

public class JbsDemoStack extends Stack {
    public JbsDemoStack(final Construct parent, final String id) {
        this(parent, id, null);
    }

    public JbsDemoStack(final Construct parent, final String id, final StackProps props) {
        super(parent, id, props);

        // Create a VPC
        Vpc vpc = Vpc.Builder.create(this, "jbs-demo-vpc")
                .vpcName("jbs-demo")
                .maxAzs(2)
                .build();

        // Create a PostgreSQL database instance
        DatabaseInstance databaseInstance = DatabaseInstance.Builder.create(this, "jbs-demo-db")
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder().version(PostgresEngineVersion.VER_16).build()))
                .instanceIdentifier("jbs-demo")
                .instanceType(InstanceType.of(InstanceClass.T4G, InstanceSize.MICRO))
                .databaseName("jbs")
                .vpc(vpc)
                .build();

        Secret dbPassword = Secret.fromSecretsManager(databaseInstance.getSecret(), "password");
        Secret dbUserName = Secret.fromSecretsManager(databaseInstance.getSecret(), "username");
        ISecret gitAppSecrets = software.amazon.awscdk.services.secretsmanager.Secret.fromSecretNameV2(this, "github-secret", "prod/github-app");
        var app = ApplicationLoadBalancedFargateService.Builder.create(this, "jbs-demo-app")
                .serviceName("jbs-demo-console")
                .loadBalancerName("jbs-demo-console")
                .desiredCount(1)
                .taskImageOptions(ApplicationLoadBalancedTaskImageOptions.builder()
                        .containerName("jbs-demo-console")
                        .containerPort(80)
                        .image(ContainerImage.fromRegistry("quay.io/sdouglas/jbs-management-console:dev"))
                        .environment(Map.of(
                                "QUARKUS_HTTP_PORT", "80",
                                "QUARKUS_DATASOURCE_DB_KIND", "postgresql",
                                "QUARKUS_DATASOURCE_JDBC_URL", "jdbc:postgresql://" + databaseInstance.getDbInstanceEndpointAddress() + ":" + databaseInstance.getDbInstanceEndpointPort() + "/jbs?loggerLevel=OFF",
                                "KUBE_DISABLED", "true"
                        ))
                        .secrets(Map.of("QUARKUS_DATASOURCE_PASSWORD", dbPassword,
                                "QUARKUS_DATASOURCE_USERNAME", dbUserName,
                                "QUARKUS_GITHUB_APP_APP_ID", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_APP_ID"),
                                "QUARKUS_GITHUB_APP_APP_NAME", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_APP_NAME"),
                                "QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL"),
                                "QUARKUS_GITHUB_APP_WEBHOOK_SECRET", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_WEBHOOK_SECRET"),
                                "QUARKUS_GITHUB_APP_PRIVATE_KEY", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_PRIVATE_KEY")))
                        .build())
                .vpc(vpc)
                .publicLoadBalancer(true)
                .build();
        databaseInstance.getConnections().allowFrom(app.getService().getConnections(), Port.tcp(5432));
    }
}
