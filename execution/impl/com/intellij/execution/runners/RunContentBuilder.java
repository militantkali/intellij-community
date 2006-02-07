/*
 * Copyright (c) 2000-2004 by JetBrains s.r.o. All Rights Reserved.
 * Use is subject to license terms.
 */
package com.intellij.execution.runners;

import com.intellij.diagnostic.logging.LogConsole;
import com.intellij.diagnostic.logging.LogConsoleManager;
import com.intellij.diagnostic.logging.LogFilesManager;
import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.*;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.CloseAction;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author dyoma
 */
public class RunContentBuilder implements LogConsoleManager {
  public static final Icon DEFAULT_RERUN_ICON = IconLoader.getIcon("/actions/refreshUsages.png");
  private final JavaProgramRunner myRunner;
  private final Project myProject;
  private final ArrayList<Disposable> myDisposeables = new ArrayList<Disposable>();
  private final ArrayList<AnAction> myRunnerActions = new ArrayList<AnAction>();
  private Icon myRerunIcon = DEFAULT_RERUN_ICON;
  private boolean myReuseProhibited = false;
  private ExecutionResult myExecutionResult;
  private JComponent myComponent;
  private RunProfile myRunProfile;
  private RunnerSettings myRunnerSettings;
  private ConfigurationPerRunnerSettings myConfigurationSettings;

  private Map<String, LogConsole> myLogIndexMap = new HashMap<String, LogConsole>();
  private final LogFilesManager myManager;

  public RunContentBuilder(final Project project, final JavaProgramRunner runner) {
    myProject = project;
    myRunner = runner;
    myManager = new LogFilesManager(project, this);
  }

  public ExecutionResult getExecutionResult() {
    return myExecutionResult;
  }

  public void setExecutionResult(final ExecutionResult executionResult) {
    myExecutionResult = executionResult;
  }

  public void setRunProfile(final RunProfile runProfile,
                            RunnerSettings runnerSettings,
                            ConfigurationPerRunnerSettings configurationSettings) {
    myRunProfile = runProfile;
    myRunnerSettings = runnerSettings;
    myConfigurationSettings = configurationSettings;
    if (runProfile instanceof RunConfigurationBase) {
      myManager.registerFileMatcher((RunConfigurationBase)runProfile);
    }
  }

  public void addAction(final AnAction action) {
    if (action == null) {
      throw new IllegalArgumentException("action");
    }
    myRunnerActions.add(action);
  }

  public RunContentDescriptor createDescriptor() {
    if (myExecutionResult == null) {
      throw new IllegalStateException("Missing ExecutionResult");
    }
    if (myRunProfile == null) {
      throw new IllegalStateException("Missing RunProfile");
    }

    final JPanel panel = new JPanel(new BorderLayout(2, 0));
    panel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

    if(!ApplicationManager.getApplication().isUnitTestMode()) {
      if (myComponent == null) {
        final ExecutionConsole console = myExecutionResult.getExecutionConsole();
        if (console != null) {
          if (myRunProfile instanceof JUnitConfiguration){
            myComponent = console.getComponent();
          } else {
            if (myRunProfile instanceof RunConfigurationBase){
              RunConfigurationBase base = (RunConfigurationBase)myRunProfile;
              final ArrayList<LogFileOptions> logFiles = base.getLogFiles();
              if (!logFiles.isEmpty()){
                myComponent = new JTabbedPane();
                ((JTabbedPane)myComponent).addTab(ExecutionBundle.message("run.configuration.console.tab"), console.getComponent());
                for (LogFileOptions logFile : logFiles) {
                  if (logFile.isEnabled()) {
                    final Set<String> paths = logFile.getPaths();
                    for (String path : paths) {
                      addLogConsole(path, logFile.isSkipContent(), myProject, logFile.getName());
                    }
                  }
                }
              }
            }
            if (myComponent == null){
              myComponent = console.getComponent();
            }
          }
        }
      }
      MyRunContentDescriptor contentDescriptor = new MyRunContentDescriptor(myRunProfile, myExecutionResult, myReuseProhibited,  panel, myDisposeables.toArray(new Disposable[myDisposeables.size()]));
      if (myComponent != null) {
        panel.add(myComponent, BorderLayout.CENTER);
      }
      panel.add(createActionToolbar(contentDescriptor, panel), BorderLayout.WEST);
      return contentDescriptor;
    }

    return new MyRunContentDescriptor(myRunProfile, myExecutionResult, myReuseProhibited,  panel, myDisposeables.toArray(new Disposable[myDisposeables.size()]));
  }

  public void addLogConsole(final String path, final boolean skipContent, final Project project, final String name) {
    final LogConsole log = new LogConsole(project, new File(path), skipContent){
      public boolean isActive() {
        return ((JTabbedPane)myComponent).getSelectedComponent() == this;
      }
    };
    myDisposeables.add(log);
    log.attachStopLogConsoleTrackingListener(myExecutionResult.getProcessHandler());
    ((JTabbedPane)myComponent).addTab(ExecutionBundle.message("run.configuration.log.tab", name), log);
    myLogIndexMap.put(path, log);
  }

  public void removeLogConsole(final String path) {
    final LogConsole logConsole = myLogIndexMap.remove(path);
    logConsole.dispose();
    myComponent.remove(logConsole);
  }

  private JComponent createActionToolbar(final RunContentDescriptor contentDescriptor, final JComponent component) {
    final DefaultActionGroup actionGroup = new DefaultActionGroup();
    final RestartAction action = new RestartAction(myRunner, myRunProfile, getProcessHandler(), myRerunIcon, contentDescriptor, myRunnerSettings, myConfigurationSettings);
    action.registerShortcut(component);
    actionGroup.add(action);

    final AnAction[] profileActions = myExecutionResult.getActions();
    for (final AnAction profileAction : profileActions) {
      actionGroup.add(profileAction);
    }

    for (final AnAction anAction : myRunnerActions) {
      if (anAction != null) {
        actionGroup.add(anAction);
      }
      else {
        actionGroup.addSeparator();
      }
    }

    final AnAction stopAction = ActionManager.getInstance().getAction(IdeActions.ACTION_STOP_PROGRAM);
    actionGroup.add(stopAction);
    actionGroup.add(new CloseAction(myRunner, contentDescriptor, myProject));
    return ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, actionGroup, false).getComponent();
  }

  public ProcessHandler getProcessHandler() {
    return myExecutionResult.getProcessHandler();
  }

  /**
   * @param reuseContent see {@link RunContentDescriptor#myContent}
   */
  public RunContentDescriptor showRunContent(final RunContentDescriptor reuseContent) {
    final RunContentDescriptor descriptor = createDescriptor();
    if(reuseContent != null) descriptor.setAttachedContent(reuseContent.getAttachedContent());
    return descriptor;
  }

  private class MyRunContentDescriptor extends RunContentDescriptor {
    private final boolean myReuseProhibited;
    private final Disposable[] myAdditionalDisposables;

    public MyRunContentDescriptor(final RunProfile profile, final ExecutionResult executionResult, final boolean reuseProhibited, final JComponent component, final Disposable[] additionalDisposables) {
      super(executionResult.getExecutionConsole(), executionResult.getProcessHandler(), component, profile.getName());
      myReuseProhibited = reuseProhibited;
      myAdditionalDisposables = additionalDisposables;
    }

    public boolean isContentReuseProhibited() {
      return myReuseProhibited;
    }

    public void dispose() {
      for (final Disposable disposable : myAdditionalDisposables) {
        disposable.dispose();
      }
      myLogIndexMap.clear();
      myManager.unregisterFileMatcher();
      super.dispose();
    }
  }
}
