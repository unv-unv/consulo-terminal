package org.jetbrains.plugins.terminal;

import com.intellij.icons.AllIcons;
import com.intellij.notification.EventLog;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.awt.RelativeRectangle;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.docking.DockContainer;
import com.intellij.ui.docking.DockManager;
import com.intellij.ui.docking.DockableContent;
import com.intellij.util.ui.UIUtil;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TabbedTerminalWidget;
import com.jediterm.terminal.ui.TerminalWidget;
import consulo.util.dataholder.Key;
import org.jetbrains.plugins.terminal.vfs.TerminalSessionVirtualFileImpl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

/**
 * @author traff
 */
@Singleton
public class TerminalView
{
	private JBTabbedTerminalWidget myTerminalWidget;

	private final Project myProject;

	private TerminalDockContainer myDockContainer;

	@Inject
	public TerminalView(Project project)
	{
		myProject = project;
	}

	public static TerminalView getInstance(@Nonnull Project project)
	{
		return project.getComponent(TerminalView.class);
	}

	public void initTerminal(final ToolWindow toolWindow)
	{
		LocalTerminalDirectRunner terminalRunner = OpenLocalTerminalAction.createTerminalRunner(myProject);

		toolWindow.setToHideOnEmptyContent(true);

		if(terminalRunner != null)
		{
			Content content = createTerminalInContentPanel(terminalRunner, toolWindow);

			toolWindow.getContentManager().addContent(content);

			((ToolWindowManagerEx) ToolWindowManager.getInstance(myProject)).addToolWindowManagerListener(new
																												  ToolWindowManagerListener()
			{
				@Override
				public void toolWindowRegistered(@Nonnull String id)
				{
				}

				@Override
				public void stateChanged()
				{
					ToolWindow window = ToolWindowManager.getInstance(myProject).getToolWindow
							(TerminalToolWindowFactory.TOOL_WINDOW_ID);
					if(window != null)
					{
						boolean visible = window.isVisible();
						if(visible && toolWindow.getContentManager().getContentCount() == 0)
						{
							initTerminal(window);
						}
					}
				}
			});

			Disposer.register(myProject, new Disposable()
			{
				@Override
				public void dispose()
				{
					if(myTerminalWidget != null)
					{
						myTerminalWidget.dispose();
						myTerminalWidget = null;
					}
				}
			});
		}

		if(myDockContainer == null)
		{
			myDockContainer = new TerminalDockContainer(toolWindow);

			Disposer.register(myProject, myDockContainer);
			DockManager.getInstance(myProject).register(myDockContainer);
		}
	}

	private Content createTerminalInContentPanel(@Nullable LocalTerminalDirectRunner terminalRunner,
			final @Nonnull ToolWindow toolWindow)
	{
		SimpleToolWindowPanel panel = new SimpleToolWindowPanel(false, true)
		{
			@Override
			public Object getData(@Nonnull Key<?> dataId)
			{
				return PlatformDataKeys.HELP_ID == dataId ? EventLog.HELP_ID : super.getData(dataId);
			}
		};

		final Content content = ContentFactory.getInstance().createContent(panel, "", false);
		content.setCloseable(true);

		myTerminalWidget = terminalRunner.createTerminalWidget(content);
		myTerminalWidget.addTabListener(new TabbedTerminalWidget.TabListener()
		{
			@Override
			public void tabClosed(JediTermWidget terminal)
			{
				UIUtil.invokeLaterIfNeeded(new Runnable()
				{
					@Override
					public void run()
					{
						if(myTerminalWidget != null)
						{
							hideIfNoActiveSessions(toolWindow, myTerminalWidget);
						}
					}
				});
			}
		});

		panel.setContent(myTerminalWidget.getComponent());
		panel.addFocusListener(createFocusListener());

		ActionToolbar toolbar = createToolbar(terminalRunner, myTerminalWidget, toolWindow);
		toolbar.getComponent().addFocusListener(createFocusListener());
		toolbar.setTargetComponent(panel);
		panel.setToolbar(toolbar.getComponent());


		content.setPreferredFocusableComponent(myTerminalWidget.getComponent());

		return content;
	}

	private FocusListener createFocusListener()
	{
		return new FocusListener()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				JComponent component = getComponentToFocus();
				if(component != null)
				{
					component.requestFocusInWindow();
				}
			}

			@Override
			public void focusLost(FocusEvent e)
			{

			}
		};
	}

	private JComponent getComponentToFocus()
	{
		return myTerminalWidget != null ? myTerminalWidget.getComponent() : null;
	}

	public void openLocalSession(Project project, ToolWindow terminal)
	{
		LocalTerminalDirectRunner terminalRunner = OpenLocalTerminalAction.createTerminalRunner(project);
		openSession(terminal, terminalRunner);
	}

	private void openSession(@Nonnull ToolWindow toolWindow, @Nonnull AbstractTerminalRunner terminalRunner)
	{
		if(myTerminalWidget == null)
		{
			toolWindow.getContentManager().removeAllContents(true);
			final Content content = createTerminalInContentPanel(null, toolWindow);
			toolWindow.getContentManager().addContent(content);
		}
		else
		{
			terminalRunner.openSession(myTerminalWidget);
		}

		toolWindow.activate(new Runnable()
		{
			@Override
			public void run()
			{

			}
		}, true);
	}

	private ActionToolbar createToolbar(@Nullable final LocalTerminalDirectRunner terminalRunner,
			@Nonnull final JBTabbedTerminalWidget terminal,
			@Nonnull ToolWindow toolWindow)
	{
		DefaultActionGroup group = new DefaultActionGroup();

		if(terminalRunner != null)
		{
			group.add(new NewSession(terminalRunner, terminal));
			group.add(new CloseSession(terminal, toolWindow));
		}

		return ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, group, false);
	}

	public void createNewSession(Project project, final AbstractTerminalRunner terminalRunner)
	{
		final ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal");

		toolWindow.activate(new Runnable()
		{
			@Override
			public void run()
			{
				openSession(toolWindow, terminalRunner);
			}
		}, true);
	}

	private static void hideIfNoActiveSessions(@Nonnull final ToolWindow toolWindow,
			@Nonnull JBTabbedTerminalWidget terminal)
	{
		if(terminal.isNoActiveSessions())
		{
			toolWindow.getContentManager().removeAllContents(true);
		}
	}


	private static class NewSession extends DumbAwareAction
	{
		private final LocalTerminalDirectRunner myTerminalRunner;
		private final TerminalWidget myTerminal;

		public NewSession(@Nonnull LocalTerminalDirectRunner terminalRunner, @Nonnull TerminalWidget terminal)
		{
			super("New Session", "Create New Terminal Session", AllIcons.General.Add);
			myTerminalRunner = terminalRunner;
			myTerminal = terminal;
		}

		@Override
		public void actionPerformed(AnActionEvent e)
		{
			myTerminalRunner.openSession(myTerminal);
		}
	}

	private class CloseSession extends DumbAwareAction
	{
		private final JBTabbedTerminalWidget myTerminal;
		private ToolWindow myToolWindow;

		public CloseSession(@Nonnull JBTabbedTerminalWidget terminal, @Nonnull ToolWindow toolWindow)
		{
			super("Close Session", "Close Terminal Session", AllIcons.Actions.Delete);
			myTerminal = terminal;
			myToolWindow = toolWindow;
		}

		@Override
		public void actionPerformed(AnActionEvent e)
		{
			myTerminal.closeCurrentSession();

			hideIfNoActiveSessions(myToolWindow, myTerminal);
		}
	}

	/**
	 * @author traff
	 */
	public class TerminalDockContainer implements DockContainer
	{
		private ToolWindow myTerminalToolWindow;

		public TerminalDockContainer(ToolWindow toolWindow)
		{
			myTerminalToolWindow = toolWindow;
		}

		@Override
		public RelativeRectangle getAcceptArea()
		{
			return new RelativeRectangle(myTerminalToolWindow.getComponent());
		}

		@Override
		public RelativeRectangle getAcceptAreaFallback()
		{
			return getAcceptArea();
		}

		@Nonnull
		@Override
		public ContentResponse getContentResponse(@Nonnull DockableContent content, RelativePoint point)
		{
			return isTerminalSessionContent(content) ? ContentResponse.ACCEPT_MOVE : ContentResponse.DENY;
		}

		@Override
		public JComponent getContainerComponent()
		{
			return myTerminalToolWindow.getComponent();
		}

		@Override
		public void add(@Nonnull DockableContent content, RelativePoint dropTarget)
		{
			if(isTerminalSessionContent(content))
			{
				TerminalSessionVirtualFileImpl terminalFile = (TerminalSessionVirtualFileImpl) content.getKey();
				myTerminalWidget.addTab(terminalFile.getName(), terminalFile.getTerminal());
				terminalFile.getTerminal().setNextProvider(myTerminalWidget);
			}
		}

		private boolean isTerminalSessionContent(DockableContent content)
		{
			return content.getKey() instanceof TerminalSessionVirtualFileImpl;
		}

		@Override
		public void closeAll()
		{

		}

		@Override
		public void addListener(Listener listener, Disposable parent)
		{

		}

		@Override
		public boolean isEmpty()
		{
			return false;
		}

		@Nullable
		@Override
		public Image startDropOver(@Nonnull DockableContent content, RelativePoint point)
		{
			return null;
		}

		@Nullable
		@Override
		public Image processDropOver(@Nonnull DockableContent content, RelativePoint point)
		{
			return null;
		}

		@Override
		public void resetDropOver(@Nonnull DockableContent content)
		{

		}

		@Override
		public boolean isDisposeWhenEmpty()
		{
			return false;
		}

		@Override
		public void showNotify()
		{

		}

		@Override
		public void hideNotify()
		{

		}

		@Override
		public void dispose()
		{

		}
	}
}
