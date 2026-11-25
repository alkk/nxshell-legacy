/**
 * NetXMS - open source network management system
 * Copyright (C) 2003-2025 Raden Solutions
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.netxms;

import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Properties;
import org.netxms.base.VersionInfo;
import org.netxms.client.NXCException;
import org.netxms.client.NXCSession;
import org.python.core.Py;
import org.python.core.PyException;
import org.python.core.PySystemState;
import org.python.core.imp;
import org.python.util.InteractiveConsole;

/**
 * nxshell launcher
 */
public class Shell
{
   private static final String DEFAULT_SERVER = "127.0.0.1";
   private static final String DEFAULT_LOGIN = "admin";

   private Properties configuration = new Properties();
   private String optServer;
   private String optPort;
   private String optLogin;
   private String optPassword;
   private String optToken;

   public static void main(String[] args)
   {
      final Shell shell = new Shell();
      try
      {
         shell.configuration.putAll(loadProperties("nxshell.properties"));
         String customPropertyFile = System.getProperty("nxshell.properties");
         if (customPropertyFile != null)
            shell.configuration.putAll(loadProperties(customPropertyFile));
         shell.configuration.putAll(System.getProperties());
         shell.run(args);
      }
      catch(PyException e)
      {
         // Exits with the requested code on SystemExit, prints Python traceback otherwise
         Py.printException(e);
         System.exit(1);
      }
      catch(Exception e)
      {
         System.err.println("Error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
         System.exit(1);
      }
   }

   private void run(String[] args) throws IOException, NXCException
   {
      initJython(args);

      if (!isInteractive())
      {
         PySystemState systemState = Py.getSystemState();
         systemState.ps1 = systemState.ps2 = Py.EmptyString;
      }

      readCredentials();

      final NXCSession session = connect();

      final InteractiveConsole console = createInterpreter();

      console.set("session", session);
      console.set("s", session);

      if (args.length == 0)
      {
         console.interact(getBanner(), null);
      }
      else
      {
         console.execfile(args[0]);
      }
      console.cleanup();

      session.disconnect();
   }

   private void readCredentials()
   {
      optServer = configuration.getProperty("netxms.server");
      optPort = configuration.getProperty("netxms.port");
      optLogin = configuration.getProperty("netxms.login");
      optPassword = configuration.getProperty("netxms.password");
      optToken = configuration.getProperty("netxms.token");

      if (isInteractive())
      {
         final Console console = System.console();
         if (optServer == null)
         {
            optServer = console.readLine("Server IP [127.0.0.1]: ");
            if (optPort == null)
            {
               optPort = console.readLine("Server TCP port [4701]: ");
            }
         }
         if ((optLogin == null) && (optToken == null))
         {
            optLogin = console.readLine("Login [admin]: ");
         }
         if ((optPassword == null) && (optToken == null))
         {
            final char[] passwordChars = console.readPassword("Password: ");
            if (passwordChars == null)
            {
               optPassword = "";
            }
            else
            {
               optPassword = new String(passwordChars);
            }
         }
      }
      if (optServer == null || optServer.length() == 0)
      {
         optServer = DEFAULT_SERVER;
      }
      if (optLogin == null || optLogin.length() == 0)
      {
         optLogin = DEFAULT_LOGIN;
      }
      if (optPassword == null)
      {
         optPassword = "";
      }
   }

   private String getBanner()
   {
      return "NetXMS " + VersionInfo.version() + " Interactive Shell";
   }

   private NXCSession connect() throws IOException, NXCException
   {
      boolean enableCompression = true;
      String enableCompressionOption = configuration.getProperty("netxms.enableCompression");
      if (enableCompressionOption != null)
      {
         enableCompression = Boolean.parseBoolean(enableCompressionOption);
      }

      boolean sync = true;
      String syncOption = configuration.getProperty("netxms.syncObjects");
      if (syncOption != null)
      {
         sync = Boolean.parseBoolean(syncOption);
      }

      final InetSocketAddress serverAddress = NXCSession.parseConnectionAddress(optServer);
      final String hostName = serverAddress.getHostString();
      int port = serverAddress.getPort();
      if ((optPort != null) && !optPort.isEmpty())
      {
         // Explicitly given port overrides port taken from server address
         try
         {
            port = Integer.parseInt(optPort);
         }
         catch(NumberFormatException e)
         {
            throw new IllegalArgumentException("Invalid port number \"" + optPort + "\"");
         }
         if ((port <= 0) || (port > 65535))
            throw new IllegalArgumentException("Port number " + port + " out of range (1-65535)");
      }

      final NXCSession session = new NXCSession(hostName, port, enableCompression);
      session.connect();
      if (optToken != null)
         session.login(optToken);
      else
         session.login(optLogin, optPassword);
      if (sync)
      {
         session.syncObjects();
         session.syncUserDatabase();
      }
      return session;
   }

   private boolean isInteractive()
   {
      if (configuration.getProperty("nxshell.interactive") != null)
         return true;
      // Jython's own isatty() check always fails on modern JDKs (reflective access to System.in is blocked)
      final Console console = System.console();
      if (console == null)
         return false;
      try
      {
         // JDK 22+ may return a console for redirected streams; isTerminal() tells them apart
         return (Boolean)Console.class.getMethod("isTerminal").invoke(console);
      }
      catch(ReflectiveOperationException e)
      {
         return true;
      }
   }

   private InteractiveConsole createInterpreter()
   {
      final InteractiveConsole console = new InteractiveConsole();
      Py.getSystemState().__setattr__("_jy_interpreter", Py.java2py(console));
      imp.load("site");

      console.exec("from org.netxms.base import *");
      console.exec("from org.netxms.client import *");
      console.exec("from org.netxms.client.agent.config import *");
      console.exec("from org.netxms.client.asset import *");
      console.exec("from org.netxms.client.businessservices import *");
      console.exec("from org.netxms.client.constants import *");
      console.exec("from org.netxms.client.dashboards import *");
      console.exec("from org.netxms.client.datacollection import *");
      console.exec("from org.netxms.client.events import *");
      console.exec("from org.netxms.client.log import *");
      console.exec("from org.netxms.client.maps import *");
      console.exec("from org.netxms.client.maps.configs import *");
      console.exec("from org.netxms.client.maps.elements import *");
      console.exec("from org.netxms.client.mt import *");
      console.exec("from org.netxms.client.objects import *");
      console.exec("from org.netxms.client.objects.configs import *");
      console.exec("from org.netxms.client.objects.interfaces import *");
      console.exec("from org.netxms.client.objects.queries import *");
      console.exec("from org.netxms.client.objecttools import *");
      console.exec("from org.netxms.client.packages import *");
      console.exec("from org.netxms.client.reporting import *");
      console.exec("from org.netxms.client.search import *");
      console.exec("from org.netxms.client.server import *");
      console.exec("from org.netxms.client.services import *");
      console.exec("from org.netxms.client.snmp import *");
      console.exec("from org.netxms.client.topology import *");
      console.exec("from org.netxms.client.users import *");
      console.exec("from org.netxms.client.xml import *");

      console.set("__name__", "__nxshell__");

      return console;
   }

   private void initJython(String[] args) throws IOException
   {
      final Properties postProperties = new Properties();
      File cacheDir = new File(System.getProperty("user.home"), ".cache/nxshell");
      if (!cacheDir.isDirectory() && !cacheDir.mkdirs())
         cacheDir = Files.createTempDirectory("nxshell").toFile();
      postProperties.setProperty("python.cachedir", cacheDir.getPath());
      postProperties.setProperty("python.cachedir.skip", "false");
      PySystemState.initialize(PySystemState.getBaseProperties(), postProperties, args);
   }

   /**
    * Load properties from file without throwing exception
    *
    * @param name property file name
    * @return properties loaded from file or empty properties
    */
   private static Properties loadProperties(String name)
   {
      Properties properties = new Properties();
      try (InputStream stream = openPropertiesFile(name))
      {
         if (stream != null)
            properties.load(stream);
      }
      catch(Exception e)
      {
         System.err.println("Error loading properties file " + name + ": " + e.getMessage());
      }
      return properties;
   }

   private static InputStream openPropertiesFile(String name) throws IOException
   {
      InputStream stream = Shell.class.getClassLoader().getResourceAsStream(name);
      if (stream != null)
         return stream;
      File f = new File(name);
      return f.isFile() ? new FileInputStream(f) : null;
   }
}
