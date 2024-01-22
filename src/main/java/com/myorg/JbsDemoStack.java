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
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class JbsDemoStack extends Stack {
    public static final String IMAGE_SHA = "e6374e5f1ea1d954a4d4bdec8324eb5d5f980203aacf803f7efd5385aebfd702";

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

        var role = Role.Builder.create(this, "jbs-role")
                .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").region("us-east-1").build())
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("AmazonS3ReadOnlyAccess"))).build();

        Secret dbPassword = Secret.fromSecretsManager(databaseInstance.getSecret(), "password");
        Secret dbUserName = Secret.fromSecretsManager(databaseInstance.getSecret(), "username");
        ISecret gitAppSecrets = software.amazon.awscdk.services.secretsmanager.Secret.fromSecretNameV2(this, "github-secret", "prod/github-app");
        ISecret adminPasswordSecrets = software.amazon.awscdk.services.secretsmanager.Secret.fromSecretNameV2(this, "admin-secret", "prod/admin-password");
        ISecret kubeToken = software.amazon.awscdk.services.secretsmanager.Secret.fromSecretNameV2(this, "kube", "kube");
        var app = ApplicationLoadBalancedFargateService.Builder.create(this, "jbs-demo-app")
                .serviceName("jbs-demo-console")
                .loadBalancerName("jbs-demo-console")
                .desiredCount(1)
                .memoryLimitMiB(2048)
                .cpu(1024)
                .taskImageOptions(ApplicationLoadBalancedTaskImageOptions.builder()
                        .containerName("jbs-demo-console")
                        .containerPort(80)
                        .taskRole(role)
                        .image(ContainerImage.fromRegistry("quay.io/sdouglas/jbs-management-console@sha256:" + IMAGE_SHA))
                        .environment(Map.of(
                                "QUARKUS_HTTP_PORT", "80",
                                "QUARKUS_DATASOURCE_DB_KIND", "postgresql",
                                "QUARKUS_DATASOURCE_JDBC_URL", "jdbc:postgresql://" + databaseInstance.getDbInstanceEndpointAddress() + ":" + databaseInstance.getDbInstanceEndpointPort() + "/jbs?loggerLevel=OFF",
                                "QUARKUS_KUBERNETES_CLIENT_TRUST_CERTS", "true"
                        ))
                        .secrets(Map.ofEntries(Map.entry("QUARKUS_DATASOURCE_PASSWORD", dbPassword),
                                Map.entry("QUARKUS_DATASOURCE_USERNAME", dbUserName),
                                Map.entry("QUARKUS_GITHUB_APP_APP_ID", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_APP_ID")),
                                Map.entry("QUARKUS_GITHUB_APP_APP_NAME", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_APP_NAME")),
                                Map.entry("QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_WEBHOOK_PROXY_URL")),
                                Map.entry("QUARKUS_GITHUB_APP_WEBHOOK_SECRET", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_WEBHOOK_SECRET")),
                                Map.entry("QUARKUS_GITHUB_APP_PRIVATE_KEY", Secret.fromSecretsManager(gitAppSecrets, "QUARKUS_GITHUB_APP_PRIVATE_KEY")),
                                Map.entry("QUARKUS_KUBERNETES_CLIENT_API_SERVER_URL", Secret.fromSecretsManager(kubeToken, "QUARKUS_KUBERNETES_CLIENT_API_SERVER_URL")),
                                Map.entry("QUARKUS_KUBERNETES_CLIENT_TOKEN", Secret.fromSecretsManager(kubeToken, "QUARKUS_KUBERNETES_CLIENT_TOKEN")),
                                Map.entry("QUARKUS_KUBERNETES_CLIENT_NAMESPACE", Secret.fromSecretsManager(kubeToken, "QUARKUS_KUBERNETES_CLIENT_NAMESPACE")),
                                Map.entry("QUARKUS_KUBERNETES_CLIENT_USERNAME", Secret.fromSecretsManager(kubeToken, "QUARKUS_KUBERNETES_CLIENT_USERNAME")),
                                Map.entry("JBS_ADMIN_PASSWORD", Secret.fromSecretsManager(adminPasswordSecrets, "JBS_ADMIN_PASSWORD"))))
                        .build())
                .vpc(vpc)
                .publicLoadBalancer(true)
                .build();
        databaseInstance.getConnections().allowFrom(app.getService().getConnections(), Port.tcp(5432));
    }
}
