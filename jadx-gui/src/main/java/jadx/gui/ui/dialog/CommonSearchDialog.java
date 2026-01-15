package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;

import jadx.api.JavaNode;
import jadx.gui.logs.LogOptions;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.JResSearchNode;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.codearea.AbstractCodeArea;
import jadx.gui.ui.panel.ProgressPanel;
import jadx.gui.ui.tab.TabsController;
import jadx.gui.utils.CacheObject;
import jadx.gui.utils.JNodeCache;
import jadx.gui.utils.JumpPosition;
import jadx.gui.utils.NLS;
import jadx.gui.utils.UiUtils;
import jadx.gui.utils.ui.NodeLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JOptionPane;
import java.util.prefs.Preferences;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.swing.JSplitPane;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import org.fife.ui.rtextarea.RTextScrollPane;

import static javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED;
import static javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED;

public abstract class CommonSearchDialog extends JFrame {
	private static final Logger LOG = LoggerFactory.getLogger(CommonSearchDialog.class);
	private static final long serialVersionUID = 8939332306115370276L;

	protected final transient TabsController tabsController;
	protected final transient CacheObject cache;
	protected final transient MainWindow mainWindow;
	protected final transient Font codeFont;
	protected final transient String windowTitle;

	protected ResultsModel resultsModel;
	protected ResultsTable resultsTable;
	protected JLabel resultsInfoLabel;
	protected JLabel progressInfoLabel;
	protected ProgressPanel progressPane;
 
	private RSyntaxTextArea codePreviewArea;
	protected JLabel warnLabel;
 
	private final List<String> searchHistory = new ArrayList<>();
    private final List<String> searchFavorites = new ArrayList<>();
    private static final int MAX_HISTORY = 20;
    private static final String PREF_KEY_HISTORY = "jadx_search_history";
    private static final String PREF_KEY_FAVORITES = "jadx_search_favorites";
    private final Preferences prefs = Preferences.userNodeForPackage(CommonSearchDialog.class);

	private SearchContext highlightContext;

	public CommonSearchDialog(MainWindow mainWindow, String title) {
		this.mainWindow = mainWindow;
		this.tabsController = mainWindow.getTabsController();
		this.cache = mainWindow.getCacheObject();
		this.codeFont = mainWindow.getSettings().getCodeFont();
		this.windowTitle = title;
		UiUtils.setWindowIcons(this);
		updateTitle("");
	}

	protected abstract void openInit();

	protected abstract void loadFinished();

	protected abstract void loadStart();

	public void loadWindowPos() {
		if (!mainWindow.getSettings().loadWindowPos(this)) {
			setSize(800, 500);
		}
	}

	private void updateTitle(String searchText) {
		if (searchText == null || searchText.isEmpty() || searchText.trim().isEmpty()) {
			setTitle(windowTitle);
		} else {
			setTitle(windowTitle + ": " + searchText);
		}
	}

	public void updateHighlightContext(String text, boolean caseSensitive, boolean regexp, boolean wholeWord) {
		updateTitle(text);
		if (text != null && !text.trim().isEmpty()) {
            addToHistory(text);
        }
		highlightContext = new SearchContext(text);
		highlightContext.setMatchCase(caseSensitive);
		highlightContext.setWholeWord(wholeWord);
		highlightContext.setRegularExpression(regexp);
		highlightContext.setMarkAll(true);
	}

	public void disableHighlight() {
		highlightContext = null;
	}

	protected void registerInitOnOpen() {
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent e) {
				SwingUtilities.invokeLater(CommonSearchDialog.this::openInit);
			}
		});
	}

	protected void openSelectedItem() {
		JNode node = getSelectedNode();
		if (node == null) {
			return;
		}
		openItem(node);
	}

	protected void openItem(JNode node) {
		if (node instanceof JResSearchNode) {
			JumpPosition jmpPos = new JumpPosition(((JResSearchNode) node).getResNode(), node.getPos());
			tabsController.codeJump(jmpPos);
		} else {
			tabsController.codeJump(node);
		}
		if (!mainWindow.getSettings().isKeepCommonDialogOpen()) {
			dispose();
		}
	}

	@Nullable
	private JNode getSelectedNode() {
		try {
			int selectedId = resultsTable.getSelectedRow();
			if (selectedId == -1 || selectedId >= resultsTable.getRowCount()) {
				return null;
			}
			return (JNode) resultsModel.getValueAt(selectedId, 0);
		} catch (Exception e) {
			LOG.error("Failed to get results table selected object", e);
			return null;
		}
	}

	@Override
	public void dispose() {
		mainWindow.getSettings().saveWindowPos(this);
		super.dispose();
	}

	protected void initCommon() {
		UiUtils.addEscapeShortCutToDispose(this);
	}

	protected void copyAllSearchResults() {
		StringBuilder sb = new StringBuilder();
		Set<String> uniqueRefs = new HashSet<>();
		for (JNode node : resultsModel.rows) {
			JavaNode javaNode = node.getJavaNode();
			if (javaNode != null) {
				String codeNodeRef = javaNode.getCodeNodeRef().toString();
				if (uniqueRefs.add(codeNodeRef)) {
					sb.append(codeNodeRef).append("\n");
				}
			}
		}
		UiUtils.copyToClipboard(sb.toString());
	}
	
	    

	protected void copyAllCodeResults() {
		StringBuilder sb = new StringBuilder();
		for (JNode node : resultsModel.rows) {
	
			if (node.hasDescString()) {

				sb.append(node.makeDescString()).append("\n");
			
			}
		}
	
		UiUtils.copyToClipboard(sb.toString());
	}


  

@NotNull
	protected JPanel initButtonsPanel() {
		loadHistoryAndFavorites();

		progressPane = new ProgressPanel(mainWindow, false);

		JButton cancelButton = new JButton(NLS.str("search_dialog.cancel"));
		cancelButton.addActionListener(event -> dispose());
		JButton openBtn = new JButton(NLS.str("search_dialog.open"));
		openBtn.addActionListener(event -> openSelectedItem());
		getRootPane().setDefaultButton(openBtn);
		JButton copyBtn = new JButton(NLS.str("search_dialog.copy"));
		copyBtn.addActionListener(event -> copyAllSearchResults());

		JButton copyAllCodeBtn = new JButton("CopyCode");
		copyAllCodeBtn.addActionListener(event -> copyAllCodeResults());

		JButton historyBtn = new JButton("History");
		historyBtn.addActionListener(e -> {
			JPopupMenu menu = new JPopupMenu();
			if (searchHistory.isEmpty()) {
				JMenuItem item = new JMenuItem("No History");
				item.setEnabled(false);
				menu.add(item);
			} else {
				for (String s : searchHistory) {
					JMenuItem item = new JMenuItem(s);
					item.addActionListener(ev -> onHistorySelected(s));
					menu.add(item);
				}
				menu.addSeparator();
				JMenuItem clearItem = new JMenuItem("Clear History");
				clearItem.addActionListener(ev -> {
					searchHistory.clear();
					saveHistory();
				});
				menu.add(clearItem);
			}
			menu.show(historyBtn, 0, historyBtn.getHeight());
		});

		JButton favBtn = new JButton("Favorites");
		favBtn.addActionListener(e -> {
			JPopupMenu menu = new JPopupMenu();
			String currentSearch = highlightContext != null ? highlightContext.getSearchFor() : null;

			JMenuItem addItem = new JMenuItem("Add Current Search");
			addItem.setEnabled(currentSearch != null && !currentSearch.trim().isEmpty());
			addItem.addActionListener(ev -> {
				if (currentSearch != null && !searchFavorites.contains(currentSearch)) {
					searchFavorites.add(currentSearch);
					saveFavorites();
				}
			});
			menu.add(addItem);
			menu.addSeparator();

			if (searchFavorites.isEmpty()) {
				JMenuItem empty = new JMenuItem("No Favorites");
				empty.setEnabled(false);
				menu.add(empty);
			} else {
				for (String s : searchFavorites) {
					JMenuItem item = new JMenuItem(s);
					item.addActionListener(ev -> onHistorySelected(s));
					menu.add(item);
				}
				menu.addSeparator();
				JMenuItem clearFav = new JMenuItem("Clear Favorites");
				clearFav.addActionListener(ev -> {
					searchFavorites.clear();
					saveFavorites();
				});
				menu.add(clearFav);
			}
			menu.show(favBtn, 0, favBtn.getHeight());
		});
		

		JCheckBox cbKeepOpen = new JCheckBox(NLS.str("search_dialog.keep_open"));
		cbKeepOpen.setSelected(mainWindow.getSettings().isKeepCommonDialogOpen());
		cbKeepOpen.addActionListener(e -> mainWindow.getSettings().saveKeepCommonDialogOpen(cbKeepOpen.isSelected()));
		cbKeepOpen.setAlignmentY(Component.CENTER_ALIGNMENT);

		JPanel buttonPane = new JPanel();
		buttonPane.setLayout(new BoxLayout(buttonPane, BoxLayout.LINE_AXIS));
		buttonPane.add(cbKeepOpen);
		buttonPane.add(Box.createRigidArea(new Dimension(15, 0)));
		buttonPane.add(progressPane);
		buttonPane.add(Box.createRigidArea(new Dimension(5, 0)));
		
		
		buttonPane.add(Box.createHorizontalGlue());

		
		buttonPane.add(historyBtn);
		buttonPane.add(Box.createRigidArea(new Dimension(10, 0)));
		buttonPane.add(favBtn);
		buttonPane.add(Box.createRigidArea(new Dimension(10, 0)));
		

		buttonPane.add(copyBtn);
		buttonPane.add(Box.createRigidArea(new Dimension(10, 0)));

		buttonPane.add(copyAllCodeBtn);
		buttonPane.add(Box.createRigidArea(new Dimension(10, 0)));

		buttonPane.add(openBtn);
		buttonPane.add(Box.createRigidArea(new Dimension(10, 0)));
		buttonPane.add(cancelButton);
		return buttonPane;
	}
protected JPanel initResultsTable() {
        ResultsTableCellRenderer renderer = new ResultsTableCellRenderer();
        resultsModel = new ResultsModel();
        resultsModel.addTableModelListener(e -> updateProgressLabel(false));

        resultsTable = new ResultsTable(resultsModel, renderer);
        resultsTable.setShowHorizontalLines(false);
        resultsTable.setDragEnabled(false);
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.setColumnSelectionAllowed(false);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultsTable.setAutoscrolls(false);

        resultsTable.setDefaultRenderer(Object.class, renderer);
        Enumeration<TableColumn> columns = resultsTable.getColumnModel().getColumns();
        while (columns.hasMoreElements()) {
            TableColumn column = columns.nextElement();
            column.setCellRenderer(renderer);
        }

        resultsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    openSelectedItem();
                }
            }
        });
        resultsTable.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    openSelectedItem();
                }
            }
        });
        
       
        resultsTable.getActionMap().put("copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JNode selectedNode = getSelectedNode();
                if (selectedNode != null) {
                    UiUtils.copyToClipboard(selectedNode.makeLongString());
                }
            }
        });

       
        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateCodePreview(getSelectedNode());
            }
        });

        warnLabel = new JLabel();
        warnLabel.setForeground(Color.RED);
        warnLabel.setVisible(false);

      
        JScrollPane tableScroll = new JScrollPane(resultsTable, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED);

 
        codePreviewArea = AbstractCodeArea.getDefaultArea(mainWindow);
        codePreviewArea.setEditable(false);
        codePreviewArea.setRows(1);  
        codePreviewArea.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        RTextScrollPane previewScroll = new RTextScrollPane(codePreviewArea);
        previewScroll.setLineNumbersEnabled(false);   
        previewScroll.setFoldIndicatorEnabled(false);  
       
        previewScroll.setPreferredSize(new Dimension(0, codePreviewArea.getFont().getSize() + 15));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, previewScroll);
        splitPane.setOneTouchExpandable(false);  
        splitPane.setResizeWeight(1.0);         
        splitPane.setDividerSize(2);            

        resultsInfoLabel = new JLabel("");
        resultsInfoLabel.setFont(mainWindow.getSettings().getUiFont());

        progressInfoLabel = new JLabel("");
        progressInfoLabel.setFont(mainWindow.getSettings().getUiFont());
        progressInfoLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                mainWindow.showLogViewer(LogOptions.allWithLevel(Level.INFO));
            }
        });

        JPanel resultsActionsPanel = new JPanel();
        resultsActionsPanel.setLayout(new BoxLayout(resultsActionsPanel, BoxLayout.LINE_AXIS));
        resultsActionsPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        addResultsActions(resultsActionsPanel);

        JPanel resultsPanel = new JPanel();
        resultsPanel.setLayout(new BorderLayout());  
        resultsPanel.add(warnLabel, BorderLayout.PAGE_START);
        resultsPanel.add(splitPane, BorderLayout.CENTER);
        resultsPanel.add(resultsActionsPanel, BorderLayout.PAGE_END);
        return resultsPanel;
    }
	private void updateCodePreview(@Nullable JNode node) {
        if (codePreviewArea == null) {
            return;
        }
        if (node == null || !node.hasDescString()) {
            codePreviewArea.setText("");
            return;
        }

       
        String content = node.makeDescString();
      
        codePreviewArea.setSyntaxEditingStyle(node.getSyntaxName());
        codePreviewArea.setText(content);

        
        if (highlightContext != null) {
            SearchEngine.markAll(codePreviewArea, highlightContext);
        }
        
        
        codePreviewArea.setCaretPosition(0);
    }
	protected void addResultsActions(JPanel resultsActionsPanel) {
		resultsActionsPanel.add(Box.createRigidArea(new Dimension(20, 0)));
		resultsActionsPanel.add(resultsInfoLabel);
		resultsActionsPanel.add(Box.createRigidArea(new Dimension(20, 0)));
		resultsActionsPanel.add(progressInfoLabel);
		resultsActionsPanel.add(Box.createHorizontalGlue());
	}

	protected void updateProgressLabel(boolean complete) {
		int count = resultsModel.getRowCount();
		String statusText;
		if (complete) {
			statusText = NLS.str("search_dialog.results_complete", count);
		} else {
			statusText = NLS.str("search_dialog.results_incomplete", count);
		}
		resultsInfoLabel.setText(statusText);
	}

	protected void showSearchState() {
		resultsInfoLabel.setText(NLS.str("search_dialog.tip_searching") + "...");
	}

	protected static final class ResultsTable extends JTable {
		private static final long serialVersionUID = 3901184054736618969L;
		private final transient ResultsModel model;

		public ResultsTable(ResultsModel resultsModel, ResultsTableCellRenderer renderer) {
			super(resultsModel);
			this.model = resultsModel;
			setRowHeight(renderer.getMaxRowHeight());
		}

		public void initColumnWidth() {
			int columnCount = getColumnCount();
			int width = getParent().getWidth();
			int colWidth = model.isAddDescColumn() ? width / 2 : width;
			columnModel.getColumn(0).setPreferredWidth(colWidth);
			for (int col = 1; col < columnCount; col++) {
				columnModel.getColumn(col).setPreferredWidth(width);
			}
		}

		public void updateTable() {
			UiUtils.uiThreadGuard();
			int rowCount = getRowCount();
			if (rowCount == 0) {
				updateUI();
				return;
			}
			long start = System.currentTimeMillis();
			int width = getParent().getWidth();
			TableColumn firstColumn = columnModel.getColumn(0);
			if (model.isAddDescColumn()) {
				if (firstColumn.getWidth() > width * 0.8) {
					firstColumn.setPreferredWidth(width / 2);
				}
				TableColumn secondColumn = columnModel.getColumn(1);
				int columnMaxWidth = width * 2; 
				if (secondColumn.getWidth() < columnMaxWidth) {
					secondColumn.setPreferredWidth(columnMaxWidth);
				}
			} else {
				firstColumn.setPreferredWidth(width);
			}
			updateUI();
			if (LOG.isDebugEnabled()) {
				LOG.debug("Update results table in {}ms, count: {}", System.currentTimeMillis() - start, rowCount);
			}
		}

		@Override
		public Object getValueAt(int row, int column) {
			return model.getValueAt(row, column);
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			if (orientation == SwingConstants.HORIZONTAL) {
				return 30;
			}
			return super.getScrollableUnitIncrement(visibleRect, orientation, direction);
		}
	}

	protected static final class ResultsModel extends AbstractTableModel {
		private static final long serialVersionUID = -7821286846923903208L;
		private static final String[] COLUMN_NAMES = { NLS.str("search_dialog.col_node"), NLS.str("search_dialog.col_code") };

		private final transient List<JNode> rows = new ArrayList<>();
		private transient boolean addDescColumn;

		public void addAll(Collection<? extends JNode> nodes) {
			rows.addAll(nodes);
			if (!addDescColumn) {
				for (JNode row : rows) {
					if (row.hasDescString()) {
						addDescColumn = true;
						break;
					}
				}
			}
		}

		public void clear() {
			addDescColumn = false;
			rows.clear();
		}

		public void sort() {
			Collections.sort(rows);
		}

		public boolean isAddDescColumn() {
			return addDescColumn;
		}

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return 2;
		}

		@Override
		public String getColumnName(int index) {
			return COLUMN_NAMES[index];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			return rows.get(rowIndex);
		}
	}

	protected final class ResultsTableCellRenderer implements TableCellRenderer {
		private final NodeLabel label;
		private final RSyntaxTextArea codeArea;
		private final NodeLabel emptyLabel;
		private final Color codeSelectedColor;
		private final Color codeBackground;

		public ResultsTableCellRenderer() {
			codeArea = AbstractCodeArea.getDefaultArea(mainWindow);
			codeArea.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
			codeArea.setRows(1);
			codeBackground = codeArea.getBackground();
			codeSelectedColor = codeArea.getSelectionColor();
			label = new NodeLabel();
			label.setOpaque(true);
			label.setFont(codeArea.getFont());
			label.setHorizontalAlignment(SwingConstants.LEFT);
			emptyLabel = new NodeLabel();
			emptyLabel.setOpaque(true);
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object obj, boolean isSelected, boolean hasFocus, int row,
				int column) {
			if (obj == null || table == null) {
				return emptyLabel;
			}
			Component comp = makeCell((JNode) obj, column);
			updateSelection(table, comp, column, isSelected);
			return comp;
		}

		private void updateSelection(JTable table, Component comp, int column, boolean isSelected) {
			if (column == 1) {
				if (isSelected) {
					comp.setBackground(codeSelectedColor);
				} else {
					comp.setBackground(codeBackground);
				}
			} else {
				if (isSelected) {
					comp.setBackground(table.getSelectionBackground());
					comp.setForeground(table.getSelectionForeground());
				} else {
					comp.setBackground(table.getBackground());
					comp.setForeground(table.getForeground());
				}
			}
		}

		private Component makeCell(JNode node, int column) {
			if (column == 0) {
				label.disableHtml(node.disableHtml());
				label.setText(node.makeLongStringHtml());
				label.setToolTipText(node.getTooltip());
				label.setIcon(node.getIcon());
				return label;
			}
			if (!node.hasDescString()) {
				return emptyLabel;
			}
			codeArea.setSyntaxEditingStyle(node.getSyntaxName());
			String descStr = node.makeDescString();
			codeArea.setText(descStr);
			codeArea.setColumns(descStr.length() + 1);
			if (highlightContext != null) {
				SearchEngine.markAll(codeArea, highlightContext);
			}
			return codeArea;
		}

		public int getMaxRowHeight() {
			label.setText("Text");
			codeArea.setText("Text");
			return Math.max(getCompHeight(label), getCompHeight(codeArea));
		}

		private int getCompHeight(Component comp) {
			return Math.max(comp.getHeight(), comp.getPreferredSize().height);
		}
	}

	void progressStartCommon() {
		progressPane.setIndeterminate(true);
		progressPane.setVisible(true);
		warnLabel.setVisible(false);
	}

	void progressFinishedCommon() {
		progressPane.setVisible(false);
	}

	protected JNodeCache getNodeCache() {
		return mainWindow.getCacheObject().getNodeCache();
	}
	    protected void loadHistoryAndFavorites() {
        String histStr = prefs.get(PREF_KEY_HISTORY, "");
        if (!histStr.isEmpty()) {
            searchHistory.addAll(Arrays.asList(histStr.split("\\|\\|")));
        }
        String favStr = prefs.get(PREF_KEY_FAVORITES, "");
        if (!favStr.isEmpty()) {
            searchFavorites.addAll(Arrays.asList(favStr.split("\\|\\|")));
        }
    }

    private void saveHistory() {
        prefs.put(PREF_KEY_HISTORY, String.join("||", searchHistory));
    }

    private void saveFavorites() {
        prefs.put(PREF_KEY_FAVORITES, String.join("||", searchFavorites));
    }

    private void addToHistory(String text) {
        if (text == null || text.trim().isEmpty()) return;
        searchHistory.remove(text);
        searchHistory.add(0, text);
        if (searchHistory.size() > MAX_HISTORY) {
            searchHistory.remove(searchHistory.size() - 1);
        }
        saveHistory();
    }

    protected void onHistorySelected(String text) {
        boolean found = autoFillSearchField(this.getContentPane(), text);
        
        if (!found) {
            UiUtils.copyToClipboard(text);
        }
    }
	    private boolean autoFillSearchField(java.awt.Container container, String text) {
        for (Component comp : container.getComponents()) {
           
            if (comp instanceof javax.swing.JTextField) {
                ((javax.swing.JTextField) comp).setText(text);
               
                return true;
            }
           
            if (comp instanceof java.awt.Container) {
                if (autoFillSearchField((java.awt.Container) comp, text)) {
                    return true;
                }
            }
        }
        return false;
    }
}
