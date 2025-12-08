package io.xlogistx.jssh;

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
        int port = 22;
        
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-h") || args[i].equals("--host")) {
                if (i + 1 < args.length) host = args[++i];
            } else if (args[i].equals("-u") || args[i].equals("--user")) {
                if (i + 1 < args.length) user = args[++i];
            } else if (args[i].equals("-p") || args[i].equals("--port")) {
                if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
            } else if (args[i].contains("@")) {
                // user@host format
                String[] parts = args[i].split("@");
                user = parts[0];
                host = parts[1];
                if (host.contains(":")) {
                    String[] hostPort = host.split(":");
                    host = hostPort[0];
                    port = Integer.parseInt(hostPort[1]);
                }
            }
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
