package io.xlogistx.jssh.ui;

import io.xlogistx.jssh.ssh.SSHConnection;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for managing port forwarding tunnels
 */
public class TunnelDialog extends JDialog {
    
    private SSHConnection connection;
    private JTable tunnelTable;
    private DefaultTableModel tableModel;
    private List<TunnelInfo> tunnels = new ArrayList<>();
    
    public TunnelDialog(Frame owner, SSHConnection connection) {
        super(owner, "Port Tunnels", true);
        this.connection = connection;
        
        initUI();
        setSize(500, 400);
        setLocationRelativeTo(owner);
    }
    
    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        ((JPanel)getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Table
        tableModel = new DefaultTableModel(
            new String[] { "Type", "Local", "Remote Host", "Remote Port", "Status" }, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        tunnelTable = new JTable(tableModel);
        tunnelTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        add(new JScrollPane(tunnelTable), BorderLayout.CENTER);
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton addLocalBtn = new JButton("Add Local →");
        addLocalBtn.addActionListener(e -> addLocalTunnel());
        buttonPanel.add(addLocalBtn);
        
        JButton addRemoteBtn = new JButton("← Add Remote");
        addRemoteBtn.addActionListener(e -> addRemoteTunnel());
        buttonPanel.add(addRemoteBtn);
        
        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> removeTunnel());
        buttonPanel.add(removeBtn);
        
        add(buttonPanel, BorderLayout.NORTH);
        
        // Close button
        JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        closePanel.add(closeBtn);
        add(closePanel, BorderLayout.SOUTH);
    }
    
    private void addLocalTunnel() {
        JPanel panel = new JPanel(new BorderLayout(5, 10));

        // Help text at top
        JTextArea helpText = new JTextArea(
            "Local Port Forward: Listens on your local machine and forwards\n" +
            "connections through SSH to the remote host.\n\n" +
            "Example: Local 8080 → remote-db:3306\n" +
            "Access remote-db:3306 by connecting to localhost:8080");
        helpText.setEditable(false);
        helpText.setBackground(panel.getBackground());
        helpText.setFont(helpText.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(helpText, BorderLayout.NORTH);

        // Input fields
        JPanel inputPanel = new JPanel(new GridLayout(3, 2, 5, 5));

        JSpinner localPort = new JSpinner(new SpinnerNumberModel(8080, 1, 65535, 1));
        JTextField remoteHost = new JTextField("localhost");
        JSpinner remotePort = new JSpinner(new SpinnerNumberModel(80, 1, 65535, 1));

        inputPanel.add(new JLabel("Local Port (listen on):"));
        inputPanel.add(localPort);
        inputPanel.add(new JLabel("Remote Host (from server):"));
        inputPanel.add(remoteHost);
        inputPanel.add(new JLabel("Remote Port:"));
        inputPanel.add(remotePort);

        panel.add(inputPanel, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(this, panel,
            "Add Local Port Forward", JOptionPane.OK_CANCEL_OPTION);

        if (result == JOptionPane.OK_OPTION) {
            try {
                int lp = (Integer) localPort.getValue();
                String rh = remoteHost.getText().trim();
                int rp = (Integer) remotePort.getValue();

                if (rh.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                        "Remote host cannot be empty",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                    return;
                }

                connection.createLocalPortForward(lp, rh, rp);

                TunnelInfo info = new TunnelInfo("Local", lp, rh, rp);
                tunnels.add(info);
                tableModel.addRow(new Object[] { "Local →", lp, rh, rp, "Active" });

                JOptionPane.showMessageDialog(this,
                    "Tunnel created. Connect to localhost:" + lp + " to reach " + rh + ":" + rp + "\n\n" +
                    "Note: If the remote service is not running, you'll see\n" +
                    "'Connection refused' errors in the log when connecting.",
                    "Tunnel Active",
                    JOptionPane.INFORMATION_MESSAGE);

            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("Address already in use")) {
                    msg = "Local port " + localPort.getValue() + " is already in use.\n" +
                          "Choose a different port or close the application using it.";
                }
                JOptionPane.showMessageDialog(this,
                    "Failed to create tunnel: " + msg,
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void addRemoteTunnel() {
        JPanel panel = new JPanel(new BorderLayout(5, 10));

        // Help text at top
        JTextArea helpText = new JTextArea(
            "Remote Port Forward: Listens on the SSH server and forwards\n" +
            "connections back to your local machine.\n\n" +
            "Example: Remote 9000 → localhost:3000\n" +
            "Others can access your localhost:3000 via server:9000");
        helpText.setEditable(false);
        helpText.setBackground(panel.getBackground());
        helpText.setFont(helpText.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(helpText, BorderLayout.NORTH);

        // Input fields
        JPanel inputPanel = new JPanel(new GridLayout(3, 2, 5, 5));

        JSpinner remotePort = new JSpinner(new SpinnerNumberModel(8080, 1, 65535, 1));
        JTextField localHost = new JTextField("localhost");
        JSpinner localPort = new JSpinner(new SpinnerNumberModel(80, 1, 65535, 1));

        inputPanel.add(new JLabel("Remote Port (listen on server):"));
        inputPanel.add(remotePort);
        inputPanel.add(new JLabel("Local Host:"));
        inputPanel.add(localHost);
        inputPanel.add(new JLabel("Local Port:"));
        inputPanel.add(localPort);

        panel.add(inputPanel, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(this, panel,
            "Add Remote Port Forward", JOptionPane.OK_CANCEL_OPTION);

        if (result == JOptionPane.OK_OPTION) {
            try {
                int rp = (Integer) remotePort.getValue();
                String lh = localHost.getText().trim();
                int lp = (Integer) localPort.getValue();

                if (lh.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                        "Local host cannot be empty",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                    return;
                }

                connection.createRemotePortForward(rp, lh, lp);

                TunnelInfo info = new TunnelInfo("Remote", lp, lh, rp);
                tunnels.add(info);
                tableModel.addRow(new Object[] { "← Remote", rp, lh, lp, "Active" });

                JOptionPane.showMessageDialog(this,
                    "Tunnel created. Connections to server:" + rp + " will reach " + lh + ":" + lp + "\n\n" +
                    "Note: The server may need 'GatewayPorts yes' in sshd_config\n" +
                    "for external access.",
                    "Tunnel Active",
                    JOptionPane.INFORMATION_MESSAGE);

            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("administratively prohibited")) {
                    msg = "Server denied the remote port forward.\n" +
                          "The server may have 'AllowTcpForwarding no' or\n" +
                          "'PermitOpen' restrictions in sshd_config.";
                }
                JOptionPane.showMessageDialog(this,
                    "Failed to create tunnel: " + msg,
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void removeTunnel() {
        int row = tunnelTable.getSelectedRow();
        if (row >= 0) {
            // Note: SSHD doesn't easily support removing individual tunnels
            // In a production app, you'd need to track and manage this
            tunnels.remove(row);
            tableModel.removeRow(row);
        }
    }
    
    private static class TunnelInfo {
        String type;
        int localPort;
        String remoteHost;
        int remotePort;
        
        TunnelInfo(String type, int localPort, String remoteHost, int remotePort) {
            this.type = type;
            this.localPort = localPort;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
        }
    }
}
