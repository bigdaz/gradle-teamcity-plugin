/*
 * Copyright 2015 Rod MacKenzie
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rodm.teamcity;

import com.github.rodm.teamcity.internal.DefaultPublishConfiguration;
import com.github.rodm.teamcity.internal.DefaultSignConfiguration;
import com.github.rodm.teamcity.internal.PluginDescriptorContentsValidationAction;
import com.github.rodm.teamcity.internal.PluginDescriptorValidationAction;
import com.github.rodm.teamcity.tasks.GenerateServerPluginDescriptor;
import com.github.rodm.teamcity.tasks.ProcessDescriptor;
import com.github.rodm.teamcity.tasks.PublishPlugin;
import com.github.rodm.teamcity.tasks.ServerPlugin;
import com.github.rodm.teamcity.tasks.SignPlugin;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;

import static com.github.rodm.teamcity.TeamCityAgentPlugin.AGENT_PLUGIN_TASK_NAME;
import static com.github.rodm.teamcity.TeamCityPlugin.PLUGIN_DESCRIPTOR_DIR;
import static com.github.rodm.teamcity.TeamCityPlugin.PLUGIN_DESCRIPTOR_FILENAME;
import static com.github.rodm.teamcity.TeamCityPlugin.TEAMCITY_GROUP;
import static com.github.rodm.teamcity.TeamCityPlugin.configurePluginArchiveTask;
import static com.github.rodm.teamcity.TeamCityVersion.VERSION_2018_2;
import static com.github.rodm.teamcity.TeamCityVersion.VERSION_2020_1;
import static com.github.rodm.teamcity.TeamCityVersion.VERSION_9_0;
import static org.gradle.api.plugins.JavaPlugin.JAR_TASK_NAME;
import static org.gradle.language.base.plugins.LifecycleBasePlugin.ASSEMBLE_TASK_NAME;

public class TeamCityServerPlugin implements Plugin<Project> {

    public static final String PLUGIN_DEFINITION_PATTERN = "META-INF/build-server-plugin*.xml";

    public static final String SERVER_PLUGIN_DESCRIPTOR_DIR = PLUGIN_DESCRIPTOR_DIR + "/server";

    public static final String PROCESS_SERVER_DESCRIPTOR_TASK_NAME = "processServerDescriptor";
    public static final String GENERATE_SERVER_DESCRIPTOR_TASK_NAME = "generateServerDescriptor";
    public static final String SERVER_PLUGIN_TASK_NAME = "serverPlugin";
    public static final String PUBLISH_PLUGIN_TASK_NAME = "publishPlugin";
    public static final String SIGN_PLUGIN_TASK_NAME = "signPlugin";

    public void apply(final Project project) {
        project.getPlugins().apply(TeamCityPlugin.class);

        project.getPlugins().withType(JavaPlugin.class, new Action<JavaPlugin>() {
            @Override
            public void execute(JavaPlugin javaPlugin) {
                if (project.getPlugins().hasPlugin(TeamCityAgentPlugin.class)) {
                    throw new GradleException("Cannot apply both the teamcity-agent and teamcity-server plugins with the Java plugin");
                }
            }
        });

        configureConfigurations(project);

        TeamCityPluginExtension extension = project.getExtensions().getByType(TeamCityPluginExtension.class);
        configureDependencies(project, extension);
        TeamCityPlugin.configureJarTask(project, extension, PLUGIN_DEFINITION_PATTERN);
        configureServerPluginTasks(project, extension);
        configureSignPluginTask(project, extension);
        configurePublishPluginTask(project, extension);
        configureEnvironmentTasks(project, extension);
    }

    public static void configureConfigurations(final Project project) {
        ConfigurationContainer configurations = project.getConfigurations();
        configurations.maybeCreate("marketplace")
            .setVisible(false)
            .setDescription("Configuration for signing and publishing task dependencies.")
            .defaultDependencies(dependencies -> {
                dependencies.add(project.getDependencies().create("org.jetbrains:marketplace-zip-signer:0.1.3"));
                dependencies.add(project.getDependencies().create("org.jetbrains.intellij.plugins:structure-base:3.171"));
                dependencies.add(project.getDependencies().create("org.jetbrains.intellij.plugins:structure-teamcity:3.171"));
                dependencies.add(project.getDependencies().create("org.jetbrains.intellij:plugin-repository-rest-client:2.0.17"));
            });
    }

    public void configureDependencies(final Project project, final TeamCityPluginExtension extension) {
        project.getPlugins().withType(JavaPlugin.class, plugin -> {
            project.afterEvaluate(p -> {
                p.getDependencies().add("provided", "org.jetbrains.teamcity:server-api:" + extension.getVersion());
                if (TeamCityVersion.version(extension.getVersion(), extension.getAllowSnapshotVersions()).compareTo(VERSION_9_0) >= 0) {
                    p.getDependencies().add("provided", "org.jetbrains.teamcity:server-web-api:" + extension.getVersion());
                }
                p.getDependencies().add("testImplementation", "org.jetbrains.teamcity:tests-support:" + extension.getVersion());
            });
        });
    }

    public void configureServerPluginTasks(final Project project, final TeamCityPluginExtension extension) {
        project.getPlugins().withType(JavaPlugin.class, plugin -> {
            project.getTasks().named(JAR_TASK_NAME, Jar.class).configure(task -> {
                task.into("buildServerResources", copySpec -> {
                    copySpec.from(extension.getServer().getWeb());
                });
            });
        });

        final Provider<RegularFile> descriptorFile = project.getLayout().getBuildDirectory().file(SERVER_PLUGIN_DESCRIPTOR_DIR + "/" + PLUGIN_DESCRIPTOR_FILENAME);

        final TaskProvider<ProcessDescriptor> processDescriptor = project.getTasks().register(PROCESS_SERVER_DESCRIPTOR_TASK_NAME, ProcessDescriptor.class, new Action<ProcessDescriptor>() {
            @Override
            public void execute(ProcessDescriptor task) {
                task.getDescriptor().set(extension.getServer().getDescriptorFile());
                task.getTokens().set(extension.getServer().getTokens());
                task.getDestination().set(descriptorFile);
            }
        });

        final TaskProvider<GenerateServerPluginDescriptor> generateDescriptor = project.getTasks().register(GENERATE_SERVER_DESCRIPTOR_TASK_NAME, GenerateServerPluginDescriptor.class, new Action<GenerateServerPluginDescriptor>() {
            @Override
            public void execute(GenerateServerPluginDescriptor task) {
                task.getVersion().set(project.getProviders().provider(() -> TeamCityVersion.version(extension.getVersion(), extension.getAllowSnapshotVersions())));
                task.getDescriptor().set(project.getProviders().provider(() -> (ServerPluginDescriptor) extension.getServer().getDescriptor()));
                task.getDestination().set(descriptorFile);
            }
        });

        final TaskProvider<ServerPlugin> packagePlugin = project.getTasks().register(SERVER_PLUGIN_TASK_NAME, ServerPlugin.class, new Action<ServerPlugin>() {
            @Override
            public void execute(ServerPlugin task) {
                task.setGroup(TEAMCITY_GROUP);
                task.onlyIf(element -> extension.getServer().getDescriptor() != null || extension.getServer().getDescriptorFile().isPresent());
                task.getDescriptor().set(descriptorFile);
                task.getServer().from(project.getConfigurations().getByName("server"));
                project.getPlugins().withType(JavaPlugin.class, plugin -> {
                    task.getServer().from(project.getTasks().named(JAR_TASK_NAME));
                    task.getServer().from(project.getConfigurations().getByName("runtimeClasspath"));
                });
                if (project.getPlugins().hasPlugin(TeamCityAgentPlugin.class)) {
                    task.getAgent().from(project.getTasks().named(AGENT_PLUGIN_TASK_NAME));
                } else {
                    task.getAgent().from((project.getConfigurations().getByName("agent")));
                }
                task.with(extension.getServer().getFiles());
                task.dependsOn(processDescriptor, generateDescriptor);
            }
        });

        project.getTasks().withType(ServerPlugin.class).configureEach(task -> {
            String schemaPath = getSchemaPath(extension.getVersion(), extension.getAllowSnapshotVersions());
            task.doLast(new PluginDescriptorValidationAction(schemaPath));
            task.doLast(new PluginDescriptorContentsValidationAction());
        });

        project.getTasks().named(ASSEMBLE_TASK_NAME, task -> {
            task.dependsOn(packagePlugin);
        });

        project.getArtifacts().add("plugin", packagePlugin);

        project.afterEvaluate(p -> {
            p.getTasks().named(SERVER_PLUGIN_TASK_NAME, task -> {
                configurePluginArchiveTask((Zip) task, extension.getServer().getArchiveName());
            });
        });
    }

    private static String getSchemaPath(String version, boolean allowSnapshots) {
        TeamCityVersion teamcityVersion = TeamCityVersion.version(version, allowSnapshots);
        if (teamcityVersion.compareTo(VERSION_2020_1) >= 0) {
            return "2020.1/teamcity-server-plugin-descriptor.xsd";
        } else if (teamcityVersion.compareTo(VERSION_2018_2) >= 0) {
            return "2018.2/teamcity-server-plugin-descriptor.xsd";
        } else {
            return "teamcity-server-plugin-descriptor.xsd";
        }
    }

    private static void configureEnvironmentTasks(final Project project, final TeamCityPluginExtension extension) {
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                if (!project.getPlugins().hasPlugin(TeamCityEnvironmentsPlugin.class)) {
                    new TeamCityEnvironmentsPlugin.ConfigureEnvironmentTasksAction(extension);
                }
            }
        });
    }

    private static void configureSignPluginTask(final Project project, final TeamCityPluginExtension extension) {
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                if (extension.getServer().getSign() != null) {
                    DefaultSignConfiguration configuration = (DefaultSignConfiguration) extension.getServer().getSign();
                    Zip packagePlugin = (Zip) project.getTasks().findByName(SERVER_PLUGIN_TASK_NAME);

                    project.getTasks().register(SIGN_PLUGIN_TASK_NAME, SignPlugin.class, new Action<SignPlugin>() {
                        @Override
                        public void execute(SignPlugin task) {
                            task.setGroup(TEAMCITY_GROUP);
                            task.setClasspath(project.getConfigurations().getByName("marketplace"));
                            task.getCertificateChain().set(configuration.getCertificateChainProperty());
                            task.getPrivateKey().set(configuration.getPrivateKeyProperty());
                            task.getPassword().set(configuration.getPasswordProperty());
                            task.getPluginFile().set(packagePlugin.getArchiveFile());
                            task.dependsOn(packagePlugin);
                        }
                    });
                }
            }
        });
    }

    private static void configurePublishPluginTask(final Project project, final TeamCityPluginExtension extension) {
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                if (extension.getServer().getPublish() != null) {
                    DefaultPublishConfiguration configuration = (DefaultPublishConfiguration) extension.getServer().getPublish();
                    SignPlugin signTask = (SignPlugin) project.getTasks().findByName(SIGN_PLUGIN_TASK_NAME);
                    Zip packagePlugin = (Zip) project.getTasks().getByName(SERVER_PLUGIN_TASK_NAME);
                    Provider<RegularFile> distributionFile = signTask != null ? signTask.getSignedPluginFile() : packagePlugin.getArchiveFile();

                    project.getTasks().register(PUBLISH_PLUGIN_TASK_NAME, PublishPlugin.class, new Action<PublishPlugin>() {
                        @Override
                        public void execute(PublishPlugin task) {
                            task.setGroup(TEAMCITY_GROUP);
                            task.setClasspath(project.getConfigurations().getByName("marketplace"));
                            task.getChannels().set(configuration.getChannels());
                            task.getToken().set(configuration.getTokenProperty());
                            task.getNotes().set(configuration.getNotesProperty());
                            task.getDistributionFile().set(distributionFile);
                            if (signTask != null) {
                                task.dependsOn(signTask);
                            } else {
                                task.dependsOn(packagePlugin);
                            }
                        }
                    });
                }
            }
        });
    }
}