/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui;

import org.eclipse.jface.action.MenuManager;
import org.eclipse.oct.internal.Activator;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.menus.IMenuService;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

/**
 * Status bar contribution showing the current OCT session state.
 *
 */
public class StatusBarContribution extends WorkbenchWindowControlContribution {

	private CLabel label;
	private Composite container;
	private MenuManager menuManager;
	private Runnable unsubCreated;
	private Runnable unsubClosed;

	public StatusBarContribution() {
		super();
	}

	public StatusBarContribution(String id) {
		super(id);
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	protected Control createControl(Composite parent) {
		container = new Composite(parent, SWT.NONE) {
			@Override
			public Point computeSize(int wHint, int hHint, boolean changed) {
				Point size = super.computeSize(wHint, hHint, changed);
				size.x = size.x + 20;
				return size;
			}
		};
		GridLayout layout = new GridLayout(2, false);
		layout.marginWidth = 0;
		layout.marginHeight = 0;
		container.setLayout(layout);

		label = new CLabel(container, SWT.NONE);
		label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		label.setImage(Activator.getInstance().getImageRegistry().get(Activator.ICON_STATUS_DISCONNECT));
		label.setCursor(container.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
		updateStatusText(this.label, Status.DISCONNECT, "");

		label.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseUp(MouseEvent e) {
				if (e.button == 3) {
					showPopupMenu(e.x, e.y);
				} else {
					openSessionView();
				}
			}

			@Override
			public void mouseDoubleClick(MouseEvent e) {
				super.mouseDoubleClick(e);
			}
		});

		SessionService svc = SessionService.getInstance();
		if (svc != null) {
			unsubCreated = svc.onSessionCreated.onEvent(__ -> refresh());
			unsubClosed = svc.onSessionClosed.onEvent(__ -> refresh());
		}
		refresh();
		parent.layout(true);
		return container;
	}

	private void refresh() {
		if (label == null || label.isDisposed()) {
			return;
		}
		label.getDisplay().asyncExec(() -> {
			if (label.isDisposed()) {
				return;
			}
			SessionService svc = SessionService.getInstance();
			if (svc == null || svc.getAllInstances().isEmpty()) {
				updateStatusText(this.label, Status.DISCONNECT, "");
				return;
			}
			CollaborationInstance instance = svc.getAllInstances().values().iterator().next();
			updateStatusText(label, instance.isHost ? Status.SHARE : Status.COLLAB,
					instance.sessionData.workspace.name);
		});
	}

	private void updateStatusText(CLabel label, Status status, String wsName) {
		if (label == null || label.isDisposed()) {
			return;
		}

		label.getDisplay().syncExec(() -> {
			if (!label.isDisposed()) {
				Image img;
				String text;
				String tooltip;
				switch (status) {
				case SHARE: {
					text = "Share";
					tooltip = "Sharing " + wsName + " workspace.";
					img = Activator.getInstance().getImageRegistry().get(Activator.ICON_STATUS_SHARE);
					break;
				}
				case COLLAB: {
					text = "Collab";
					tooltip = "Callaborating with " + wsName + " workspace.";
					img = Activator.getInstance().getImageRegistry().get(Activator.ICON_STATUS_COLLAB);
					break;
				}
				default: {
					text = "OCT";
					tooltip = "Open Collaboration";
					img = Activator.getInstance().getImageRegistry().get(Activator.ICON_STATUS_DISCONNECT);
				}
				}
				label.setImage(img);
				label.setText(text);
				label.setToolTipText(tooltip);
				container.layout(true, true);
			}
		});
	}

	private void openSessionView() {
		try {
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			if (page != null) {
				page.showView(SessionView.ID, null, IWorkbenchPage.VIEW_ACTIVATE);
			}
		} catch (PartInitException e) {
			// ignore — view simply doesn't open
		}
	}

	private void showPopupMenu(int x, int y) {
		if (label == null || label.isDisposed()) {
			return;
		}
		if (menuManager == null) {
			menuManager = new MenuManager();
			IMenuService menuService = PlatformUI.getWorkbench().getService(IMenuService.class);
			if (menuService != null) {
				menuService.populateContributionManager(menuManager, "popup:org.eclipse.oct.statusBar");
			}
		}
		Menu menu = menuManager.createContextMenu(label);
		Point location = label.toDisplay(x, y);
		menu.setLocation(location);
		menu.setVisible(true);
	}

	@Override
	public void dispose() {
		if (menuManager != null) {
			IMenuService menuService = PlatformUI.getWorkbench().getService(IMenuService.class);
			if (menuService != null) {
				menuService.releaseContributions(menuManager);
			}
			menuManager.dispose();
			menuManager = null;
		}
		if (unsubCreated != null) {
			unsubCreated.run();
		}
		if (unsubClosed != null) {
			unsubClosed.run();
		}
		super.dispose();
	}

	enum Status {
		DISCONNECT, SHARE, COLLAB
	}
}
