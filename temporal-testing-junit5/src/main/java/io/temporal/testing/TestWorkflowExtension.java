/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.testing;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.metadata.POJOWorkflowImplMetadata;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerOptions;
import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * JUnit Jupiter extension that simplifies testing of Temporal workflows.
 *
 * <p>The extension manages Temporal test environment and workflow worker lifecycle, and be used
 * with both in-memory (default) and standalone temporal service (see {@link
 * Builder#useInternalService()}, {@link Builder#useExternalService()} and {@link
 * Builder#useExternalService(String)}}).
 *
 * <p>This extension can inject workflow stubs as well as instances of {@link
 * TestWorkflowEnvironment}, {@link WorkflowClient}, {@link WorkflowOptions}, {@link Worker}, into
 * test methods.
 *
 * <p>Usage example:
 *
 * <pre><code>
 * public class MyTest {
 *
 *  {@literal @}RegisterExtension
 *   public static final TestWorkflowExtension workflowExtension =
 *       TestWorkflowExtension.newBuilder()
 *           .setWorkflowTypes(MyWorkflowImpl.class)
 *           .setActivityImplementations(new MyActivities())
 *           .build();
 *
 *  {@literal @}Test
 *   public void testMyWorkflow(MyWorkflow workflow) {
 *     // Test code that calls MyWorkflow methods
 *   }
 * }
 * </code></pre>
 */
public class TestWorkflowExtension
    implements ParameterResolver, TestWatcher, BeforeEachCallback, AfterEachCallback {

  private static final String TEST_ENVIRONMENT_KEY = "testEnvironment";
  private static final String WORKER_KEY = "worker";
  private static final String TASK_QUEUE_KEY = "taskQueue";

  private final WorkerOptions workerOptions;
  private final WorkflowClientOptions workflowClientOptions;
  private final Class<?>[] workflowTypes;
  private final Object[] activityImplementations;
  private final boolean useExternalService;
  private final String target;
  private final boolean doNotStart;

  private final Set<Class<?>> supportedParameterTypes = new HashSet<>();

  private TestWorkflowExtension(Builder builder) {
    workerOptions = builder.workerOptions;
    if (builder.workflowClientOptions != null) {
      workflowClientOptions = builder.workflowClientOptions;
    } else {
      workflowClientOptions =
          WorkflowClientOptions.newBuilder().setNamespace(builder.namespace).build();
    }
    workflowTypes = builder.workflowTypes;
    activityImplementations = builder.activityImplementations;
    useExternalService = builder.useExternalService;
    target = builder.target;
    doNotStart = builder.doNotStart;

    supportedParameterTypes.add(TestWorkflowEnvironment.class);
    supportedParameterTypes.add(WorkflowClient.class);
    supportedParameterTypes.add(WorkflowOptions.class);
    supportedParameterTypes.add(Worker.class);

    for (Class<?> workflowType : workflowTypes) {
      POJOWorkflowImplMetadata metadata = POJOWorkflowImplMetadata.newInstance(workflowType);
      for (POJOWorkflowInterfaceMetadata workflowInterface : metadata.getWorkflowInterfaces()) {
        supportedParameterTypes.add(workflowInterface.getInterfaceClass());
      }
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {

    if (parameterContext.getParameter().getDeclaringExecutable() instanceof Constructor) {
      // Constructor injection is not supported
      return false;
    }

    Class<?> parameterType = parameterContext.getParameter().getType();
    return supportedParameterTypes.contains(parameterType);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {

    TestWorkflowEnvironment testEnvironment = getTestEnvironment(extensionContext);

    Class<?> parameterType = parameterContext.getParameter().getType();
    if (parameterType == TestWorkflowEnvironment.class) {
      return testEnvironment;
    } else if (parameterType == WorkflowClient.class) {
      return testEnvironment.getWorkflowClient();
    } else if (parameterType == WorkflowOptions.class) {
      String taskQueue = getTaskQueue(extensionContext);
      return WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
    } else if (parameterType == Worker.class) {
      return getWorker(extensionContext);
    } else {
      // Workflow stub
      String taskQueue = getTaskQueue(extensionContext);
      WorkflowOptions workflowOptions =
          WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      return testEnvironment.getWorkflowClient().newWorkflowStub(parameterType, workflowOptions);
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(workflowClientOptions)
            .setUseExternalService(useExternalService)
            .setTarget(target)
            .build();
    TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
    String taskQueue =
        String.format("WorkflowTest-%s-%s", context.getDisplayName(), context.getUniqueId());
    Worker worker = testEnvironment.newWorker(taskQueue, workerOptions);
    worker.registerWorkflowImplementationTypes(workflowTypes);
    worker.registerActivitiesImplementations(activityImplementations);

    if (!doNotStart) {
      testEnvironment.start();
    }

    setTestEnvironment(context, testEnvironment);
    setWorker(context, worker);
    setTaskQueue(context, taskQueue);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    TestWorkflowEnvironment testEnvironment = getTestEnvironment(context);
    testEnvironment.close();
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    TestWorkflowEnvironment testEnvironment = getTestEnvironment(context);
    System.err.println("Workflow execution histories:\n" + testEnvironment.getDiagnostics());
  }

  private TestWorkflowEnvironment getTestEnvironment(ExtensionContext context) {
    return getStore(context).get(TEST_ENVIRONMENT_KEY, TestWorkflowEnvironment.class);
  }

  private void setTestEnvironment(
      ExtensionContext context, TestWorkflowEnvironment testEnvironment) {
    getStore(context).put(TEST_ENVIRONMENT_KEY, testEnvironment);
  }

  private Worker getWorker(ExtensionContext context) {
    return getStore(context).get(WORKER_KEY, Worker.class);
  }

  private void setWorker(ExtensionContext context, Worker worker) {
    getStore(context).put(WORKER_KEY, worker);
  }

  private String getTaskQueue(ExtensionContext context) {
    return getStore(context).get(TASK_QUEUE_KEY, String.class);
  }

  private void setTaskQueue(ExtensionContext context, String taskQueue) {
    getStore(context).put(TASK_QUEUE_KEY, taskQueue);
  }

  private ExtensionContext.Store getStore(ExtensionContext context) {
    Namespace namespace =
        Namespace.create(TestWorkflowExtension.class, context.getRequiredTestMethod());
    return context.getStore(namespace);
  }

  public static class Builder {

    private static final Class<?>[] NO_WORKFLOWS = new Class<?>[0];
    private static final Object[] NO_ACTIVITIES = new Object[0];

    private WorkerOptions workerOptions = WorkerOptions.getDefaultInstance();
    private WorkflowClientOptions workflowClientOptions;
    private String namespace = "UnitTest";
    private Class<?>[] workflowTypes = NO_WORKFLOWS;
    private Object[] activityImplementations = NO_ACTIVITIES;
    private boolean useExternalService = false;
    private String target = null;
    private boolean doNotStart = false;

    private Builder() {}

    /** @see TestWorkflowEnvironment#newWorker(String, WorkerOptions) */
    public Builder setWorkerOptions(WorkerOptions options) {
      this.workerOptions = options;
      return this;
    }

    /**
     * Override {@link WorkflowClientOptions} for test environment. If set, takes precedence over
     * {@link #setNamespace(String) namespace}.
     */
    public Builder setWorkflowClientOptions(WorkflowClientOptions workflowClientOptions) {
      this.workflowClientOptions = workflowClientOptions;
      return this;
    }

    /**
     * Set Temporal namespace to use for tests, by default, {@code UnitTest} is used.
     *
     * @see WorkflowClientOptions#getNamespace()
     */
    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    /**
     * Specify workflow implementation types to register with the Temporal worker.
     *
     * @see Worker#registerWorkflowImplementationTypes(Class[])
     */
    public Builder setWorkflowTypes(Class<?>... workflowTypes) {
      this.workflowTypes = workflowTypes;
      return this;
    }

    /**
     * Specify activity implementations to register with the Temporal worker
     *
     * @see Worker#registerActivitiesImplementations(Object...)
     */
    public Builder setActivityImplementations(Object... activityImplementations) {
      this.activityImplementations = activityImplementations;
      return this;
    }

    /**
     * Switches to external Temporal service implementation with default endpoint of {@code
     * 127.0.0.1:7233}.
     *
     * @see TestEnvironmentOptions.Builder#setUseExternalService(boolean)
     * @see TestEnvironmentOptions.Builder#setTarget(String)
     * @see WorkflowServiceStubsOptions.Builder#setTarget(String)
     */
    public Builder useExternalService() {
      return useExternalService(null);
    }

    /**
     * Switches to external Temporal service implementation.
     *
     * @param target defines the endpoint which will be used for the communication with standalone
     *     Temporal service.
     * @see TestEnvironmentOptions.Builder#setUseExternalService(boolean)
     * @see TestEnvironmentOptions.Builder#setTarget(String)
     * @see WorkflowServiceStubsOptions.Builder#setTarget(String)
     */
    public Builder useExternalService(String target) {
      this.useExternalService = true;
      this.target = target;
      return this;
    }

    /** Switches to internal in-memory Temporal service implementation (default). */
    public Builder useInternalService() {
      this.useExternalService = false;
      this.target = null;
      return this;
    }

    /**
     * When set to true the {@link TestWorkflowEnvironment#start()} is not called by the extension
     * before executing the test. This to support tests that register activities and workflows with
     * workers directly instead of using only {@link TestWorkflowExtension.Builder}.
     */
    public Builder setDoNotStart(boolean doNotStart) {
      this.doNotStart = doNotStart;
      return this;
    }

    public TestWorkflowExtension build() {
      return new TestWorkflowExtension(this);
    }
  }
}
