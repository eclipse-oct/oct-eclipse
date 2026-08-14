/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.oct.internal.editor.EditorManager;
import org.eclipse.oct.internal.protocol.Peer;
import org.eclipse.oct.internal.util.OctPaths;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.eclipse.ui.part.ViewPart;

/**
 * Session view: participants in a table with color, role, file, and follow icons.
 */
public class SessionView extends ViewPart {

	public static final String ID = "org.eclipse.oct.sessionView";

	private Composite root;
	private Composite emptyPage;
	private Composite sessionPage;
	private Label roleLabel;
	private Label titleLabel;
	private org.eclipse.swt.graphics.Font roleFont;
	private TableViewer viewer;

	private Image followImage;
	private Image fileImage;
	private final Map<RGB, Image> colorDots = new HashMap<>();
	private Action followToolbarAction;

	private Runnable unsubscribeSessionCreated;
	private Runnable unsubscribeSessionClosed;
	private final List<Runnable> presenceUnsubs = new ArrayList<>();

	private record Participant(Peer peer, boolean self, boolean host, boolean canFollow) {
	}

	@Override
	public void createPartControl(Composite parent) {
		IMenuManager viewMenu = getViewSite().getActionBars().getMenuManager();
		viewMenu.add(new CommandContributionItem(new CommandContributionItemParameter(getSite(), null,
				"org.eclipse.oct.hostSession", CommandContributionItem.STYLE_PUSH)));
		viewMenu.add(new CommandContributionItem(new CommandContributionItemParameter(getSite(), null,
				"org.eclipse.oct.joinSession", CommandContributionItem.STYLE_PUSH)));
		viewMenu.add(new Separator());
		viewMenu.add(new CommandContributionItem(new CommandContributionItemParameter(getSite(), null,
				"org.eclipse.oct.closeSession", CommandContributionItem.STYLE_PUSH)));

		followImage = createFollowImage(parent.getDisplay());
		fileImage = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_FILE);
		followToolbarAction = new Action("Follow", IAction.AS_CHECK_BOX) {
			@Override
			public void run() {
				toggleToolbarFollow();
			}
		};
		followToolbarAction.setImageDescriptor(ImageDescriptor.createFromImage(followImage));
		followToolbarAction.setEnabled(false);
		followToolbarAction.setToolTipText("Follow");

		IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
		if (toolbar.find("org.eclipse.oct.hostSession") != null) {
			toolbar.insertBefore("org.eclipse.oct.hostSession", followToolbarAction);
		} else {
			toolbar.add(followToolbarAction);
		}
		getViewSite().getActionBars().updateActionBars();

		root = new Composite(parent, SWT.NONE);
		GridLayoutFactory.fillDefaults().applyTo(root);

		createEmptyPage(root);
		createSessionPage(root);

		SessionService svc = SessionService.getInstance();
		if (svc != null) {
			unsubscribeSessionCreated = svc.onSessionCreated.onEvent(instance -> {
				bindInstance(instance);
				refresh();
			});
			unsubscribeSessionClosed = svc.onSessionClosed.onEvent(__ -> refresh());
			svc.getAllInstances().values().forEach(this::bindInstance);
		}

		refresh();
	}

	private void createEmptyPage(Composite parent) {
		emptyPage = new Composite(parent, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(emptyPage);
		GridLayoutFactory.fillDefaults().margins(16, 20).applyTo(emptyPage);

		EmptySessionPanel hero = new EmptySessionPanel(emptyPage, this::runCommand);
		GridDataFactory.fillDefaults().grab(true, true).align(SWT.FILL, SWT.CENTER).applyTo(hero);
	}

	private void runCommand(String commandId) {
		var handlers = getSite().getService(org.eclipse.ui.handlers.IHandlerService.class);
		if (handlers == null) {
			return;
		}
		try {
			handlers.executeCommand(commandId, null);
		} catch (Exception ignored) {
		}
	}

	private void createSessionPage(Composite parent) {
		sessionPage = new Composite(parent, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(sessionPage);
		GridLayoutFactory.fillDefaults().margins(8, 8).spacing(0, 6).applyTo(sessionPage);

		Composite header = new Composite(sessionPage, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(header);
		GridLayoutFactory.fillDefaults().numColumns(2).spacing(8, 0).applyTo(header);

		roleLabel = new Label(header, SWT.NONE);
		org.eclipse.swt.graphics.FontData[] fd = roleLabel.getFont().getFontData();
		for (org.eclipse.swt.graphics.FontData d : fd) {
			d.setStyle(SWT.BOLD);
		}
		roleFont = new org.eclipse.swt.graphics.Font(parent.getDisplay(), fd);
		roleLabel.setFont(roleFont);
		GridDataFactory.swtDefaults().align(SWT.LEFT, SWT.CENTER).applyTo(roleLabel);

		titleLabel = new Label(header, SWT.WRAP);
		titleLabel.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DARK_SHADOW));
		GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).applyTo(titleLabel);

		Composite tableHost = new Composite(sessionPage, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(tableHost);
		TableColumnLayout columns = new TableColumnLayout();
		tableHost.setLayout(columns);

		viewer = new TableViewer(tableHost, SWT.FULL_SELECTION | SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
		Table table = viewer.getTable();
		table.setHeaderVisible(true);
		table.setLinesVisible(true);
		viewer.setContentProvider(ArrayContentProvider.getInstance());
		ColumnViewerToolTipSupport.enableFor(viewer);

		TableViewerColumn nameCol = new TableViewerColumn(viewer, SWT.NONE);
		nameCol.getColumn().setText("Participant");
		columns.setColumnData(nameCol.getColumn(), new ColumnWeightData(40, 120, true));
		nameCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				return peerName(((Participant) element).peer());
			}

			@Override
			public Image getImage(Object element) {
				Participant p = (Participant) element;
				CollaborationInstance inst = findActiveInstance(SessionService.getInstance());
				if (inst == null || p.peer().id == null) {
					return null;
				}
				return colorDot(inst.peerColors.getColor(p.peer().id));
			}
		});

		TableViewerColumn roleCol = new TableViewerColumn(viewer, SWT.NONE);
		roleCol.getColumn().setText("Role");
		columns.setColumnData(roleCol.getColumn(), new ColumnWeightData(18, 64, true));
		roleCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				Participant p = (Participant) element;
				if (p.self() && p.host()) {
					return "You · Host";
				}
				if (p.self()) {
					return "You";
				}
				if (p.host()) {
					return "Host";
				}
				return "Guest";
			}
		});

		TableViewerColumn fileCol = new TableViewerColumn(viewer, SWT.NONE);
		fileCol.getColumn().setText("File");
		columns.setColumnData(fileCol.getColumn(), new ColumnWeightData(32, 80, true));
		fileCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				String path = peerFilePath((Participant) element);
				return path == null ? "" : fileLabel(path);
			}

			@Override
			public Image getImage(Object element) {
				return peerFilePath((Participant) element) == null ? null : fileImage;
			}

			@Override
			public String getToolTipText(Object element) {
				return peerFilePath((Participant) element);
			}
		});

		TableViewerColumn followCol = new TableViewerColumn(viewer, SWT.NONE);
		followCol.getColumn().setText("Follow");
		columns.setColumnData(followCol.getColumn(), new ColumnWeightData(10, 48, true));
		followCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				return "";
			}

			@Override
			public Image getImage(Object element) {
				return isFollowing((Participant) element) ? followImage : null;
			}

			@Override
			public String getToolTipText(Object element) {
				Participant p = (Participant) element;
				if (!p.canFollow()) {
					return null;
				}
				return isFollowing(p) ? "Stop following " + peerName(p.peer()) : "Follow " + peerName(p.peer());
			}
		});

		table.addListener(SWT.MouseDown, e -> {
			TableItem item = table.getItem(new Point(e.x, e.y));
			if (item == null || !(item.getData() instanceof Participant p) || !p.canFollow()) {
				return;
			}
			if (item.getBounds(3).contains(e.x, e.y)) {
				toggleFollow(p);
			}
		});
		viewer.addDoubleClickListener(e -> {
			if (e.getSelection() instanceof IStructuredSelection sel
					&& sel.getFirstElement() instanceof Participant p && p.canFollow()) {
				toggleFollow(p);
			}
		});
	}

	private void bindInstance(CollaborationInstance instance) {
		presenceUnsubs.add(instance.onPeersChanged.onEvent(__ -> refresh()));
		if (instance.getEditorManager() != null) {
			presenceUnsubs.add(instance.getEditorManager().onPresenceChanged.onEvent(__ -> refresh()));
		}
	}

	private void refresh() {
		Display.getDefault().asyncExec(() -> {
			if (root == null || root.isDisposed()) {
				return;
			}
			CollaborationInstance instance = findActiveInstance(SessionService.getInstance());
			boolean active = instance != null;
			emptyPage.setVisible(!active);
			((org.eclipse.swt.layout.GridData) emptyPage.getLayoutData()).exclude = active;
			sessionPage.setVisible(active);
			((org.eclipse.swt.layout.GridData) sessionPage.getLayoutData()).exclude = !active;

			if (active) {
				roleLabel.setText(instance.isHost ? "Hosting" : "Collaborating");
				titleLabel.setText(displayWorkspaceName(instance));
				String selectedId = selectedPeerId();
				viewer.setInput(participants(instance));
				restoreSelection(selectedId);
			} else {
				viewer.setInput(List.of());
			}
			updateToolbar(instance);
			root.layout(true, true);
		});
	}

	private static String displayWorkspaceName(CollaborationInstance instance) {
		String name = instance.sessionData.workspace != null ? instance.sessionData.workspace.name : null;
		if (name == null || name.isBlank()) {
			name = instance.project != null ? instance.project.getName() : "Collaboration";
		}
		return name.replaceFirst("-oct-\\d+$", "");
	}

	private List<Participant> participants(CollaborationInstance instance) {
		List<Participant> rows = new ArrayList<>();
		if (instance.identity != null) {
			rows.add(new Participant(instance.identity, true, instance.isHost, false));
		} else {
			Peer you = new Peer();
			you.name = "You";
			rows.add(new Participant(you, true, instance.isHost, false));
		}
		if (!instance.isHost && instance.host != null) {
			rows.add(new Participant(instance.host, false, true, true));
		}
		for (Peer guest : instance.guests) {
			if (instance.identity != null && guest.id.equals(instance.identity.id)) {
				continue;
			}
			rows.add(new Participant(guest, false, false, true));
		}
		return rows;
	}

	private String selectedPeerId() {
		if (viewer == null || viewer.getTable().isDisposed()) {
			return null;
		}
		if (viewer.getStructuredSelection().getFirstElement() instanceof Participant p && p.peer().id != null) {
			return p.peer().id;
		}
		return null;
	}

	private void restoreSelection(String peerId) {
		if (peerId == null) {
			return;
		}
		@SuppressWarnings("unchecked")
		List<Participant> rows = (List<Participant>) viewer.getInput();
		if (rows == null) {
			return;
		}
		for (Participant p : rows) {
			if (peerId.equals(p.peer().id)) {
				viewer.getTable().setSelection(rows.indexOf(p));
				return;
			}
		}
	}

	private void updateToolbar(CollaborationInstance instance) {
		if (followToolbarAction == null) {
			return;
		}
		if (instance == null || instance.getEditorManager() == null) {
			followToolbarAction.setEnabled(false);
			followToolbarAction.setChecked(false);
			followToolbarAction.setToolTipText("Follow");
			getViewSite().getActionBars().updateActionBars();
			return;
		}
		EditorManager em = instance.getEditorManager();
		followToolbarAction.setEnabled(true);
		if (instance.isHost) {
			followToolbarAction.setChecked(em.isFollowGuestSelection());
			followToolbarAction.setToolTipText("Follow guest editors — open and activate files they open");
		} else {
			boolean following = em.getFollowingPeerId() != null;
			followToolbarAction.setChecked(following);
			followToolbarAction.setToolTipText(following ? "Stop following" : "Follow the host");
		}
		getViewSite().getActionBars().updateActionBars();
	}

	private void toggleToolbarFollow() {
		CollaborationInstance inst = findActiveInstance(SessionService.getInstance());
		if (inst == null || inst.getEditorManager() == null) {
			return;
		}
		EditorManager em = inst.getEditorManager();
		if (inst.isHost) {
			em.setFollowGuestSelection(!em.isFollowGuestSelection());
			return;
		}
		if (viewer.getStructuredSelection().getFirstElement() instanceof Participant p && p.canFollow()) {
			toggleFollow(p);
			return;
		}
		if (em.getFollowingPeerId() != null) {
			em.stopFollowing();
		} else if (inst.host != null) {
			em.followPeer(inst.host.id);
		}
	}

	private void toggleFollow(Participant p) {
		CollaborationInstance inst = findActiveInstance(SessionService.getInstance());
		if (inst == null || inst.getEditorManager() == null || !p.canFollow()) {
			return;
		}
		EditorManager em = inst.getEditorManager();
		if (p.peer().id != null && p.peer().id.equals(em.getFollowingPeerId())) {
			em.stopFollowing();
		} else {
			em.followPeer(p.peer().id);
		}
	}

	private boolean isFollowing(Participant p) {
		CollaborationInstance inst = findActiveInstance(SessionService.getInstance());
		return inst != null && inst.getEditorManager() != null && p.peer().id != null
				&& p.peer().id.equals(inst.getEditorManager().getFollowingPeerId());
	}

	private static String peerFilePath(Participant p) {
		CollaborationInstance inst = findActiveInstance(SessionService.getInstance());
		if (inst == null || inst.getEditorManager() == null) {
			return null;
		}
		return inst.getEditorManager().getPeerDocumentPath(p.peer().id);
	}

	private static String peerName(Peer peer) {
		if (peer.name != null && !peer.name.isBlank()) {
			return peer.name;
		}
		return peer.id != null ? peer.id : "?";
	}

	private static String fileLabel(String protocolPath) {
		if (protocolPath == null || protocolPath.isBlank()) {
			return "";
		}
		String n = OctPaths.normalize(protocolPath);
		int slash = n.lastIndexOf('/');
		return slash >= 0 ? n.substring(slash + 1) : n;
	}

	private Image colorDot(RGB rgb) {
		return colorDots.computeIfAbsent(rgb, color -> {
			Image img = new Image(root.getDisplay(), 16, 16);
			GC gc = new GC(img);
			try {
				gc.setBackground(root.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
				gc.fillRectangle(0, 0, 16, 16);
				gc.setAntialias(SWT.ON);
				org.eclipse.swt.graphics.Color fill = new org.eclipse.swt.graphics.Color(root.getDisplay(), color);
				gc.setBackground(fill);
				gc.fillOval(2, 2, 12, 12);
				fill.dispose();
			} finally {
				gc.dispose();
			}
			return img;
		});
	}

	private static CollaborationInstance findActiveInstance(SessionService svc) {
		if (svc == null) {
			return null;
		}
		return svc.getAllInstances().values().stream().findFirst().orElse(null);
	}

	private static Image createFollowImage(Display display) {
		Image img = new Image(display, 16, 16);
		GC gc = new GC(img);
		try {
			gc.setBackground(display.getSystemColor(SWT.COLOR_LIST_BACKGROUND));
			gc.fillRectangle(0, 0, 16, 16);
			gc.setAntialias(SWT.ON);
			gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
			gc.setLineWidth(2);
			gc.drawArc(2, 2, 11, 11, 50, 260);
			int[] arrow = { 12, 1, 15, 5, 10, 5 };
			gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
			gc.fillPolygon(arrow);
		} finally {
			gc.dispose();
		}
		return img;
	}

	@Override
	public void setFocus() {
		if (sessionPage != null && sessionPage.isVisible() && viewer != null) {
			viewer.getTable().setFocus();
		} else {
			root.setFocus();
		}
	}

	@Override
	public void dispose() {
		if (unsubscribeSessionCreated != null) {
			unsubscribeSessionCreated.run();
		}
		if (unsubscribeSessionClosed != null) {
			unsubscribeSessionClosed.run();
		}
		for (Runnable u : presenceUnsubs) {
			u.run();
		}
		presenceUnsubs.clear();
		super.dispose();
		if (roleFont != null && !roleFont.isDisposed()) {
			roleFont.dispose();
		}
		if (followImage != null && !followImage.isDisposed()) {
			followImage.dispose();
		}
		for (Image dot : colorDots.values()) {
			if (!dot.isDisposed()) {
				dot.dispose();
			}
		}
		colorDots.clear();
	}
}
