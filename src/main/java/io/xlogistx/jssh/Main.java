package io.xlogistx.jssh;

import io.xlogistx.jssh.config.JSSHConst;
import io.xlogistx.jssh.ui.MainFrame;
import javax.swing.*;

/**
 * Main entry point for JSSH - Java SSH Client
 */
public class Main {
    
    public static void main(String[] args) {
        // Set system look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fall back to default
        }
        
        // Parse command line arguments
        String host = null;
        String user = null;
        int port = JSSHConst.DEFAULT_SSH_PORT;
        
        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-h") || args[i].equals("--host")) {
                    if (i + 1 < args.length) host = args[++i];
                } else if (args[i].equals("-u") || args[i].equals("--user")) {
                    if (i + 1 < args.length) user = args[++i];
                } else if (args[i].equals("-p") || args[i].equals("--port")) {
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                } else if (args[i].contains("@")) {
                    // user@host format - use the last '@' so usernames containing '@' still work
                    int at = args[i].lastIndexOf('@');
                    if (at > 0) {
                        user = args[i].substring(0, at);
                    }
                    host = args[i].substring(at + 1);
                    int colon = host.indexOf(':');
                    // A single colon separates host:port; multiple colons mean a bare IPv6 address
                    if (colon >= 0 && colon == host.lastIndexOf(':')) {
                        port = Integer.parseInt(host.substring(colon + 1));
                        host = host.substring(0, colon);
                    }
                }
            }
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("port out of range: " + port);
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid port (" + e.getMessage() + ")");
            System.err.println("Usage: jssh [-h host] [-p port] [-u user] [user@host[:port]]");
            System.exit(1);
        }
        
        final String finalHost = host;
        final String finalUser = user;
        final int finalPort = port;
        
        // Start GUI on EDT
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            
            // If host was provided, open connection dialog
            if (finalHost != null) {
                frame.quickConnect(finalHost, finalPort, finalUser);
            }
        });
    }
}
