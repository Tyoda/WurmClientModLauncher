package org.gotti.wurmonline.clientmods.serverpacks;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;
import org.gotti.wurmunlimited.modcomm.PacketReader;
import org.gotti.wurmunlimited.modcomm.PacketWriter;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.classhooks.InvocationHandlerFactory;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;
import org.gotti.wurmunlimited.modsupport.ModClient;
import org.gotti.wurmunlimited.modsupport.console.ConsoleListener;
import org.gotti.wurmunlimited.modsupport.console.ModConsole;
import org.gotti.wurmunlimited.modsupport.packs.ModPacks;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.bytecode.Descriptor;


public class ServerPacksMod implements WurmClientMod, Initable, ConsoleListener {
	
	private static final String CONSOLE_PREFIX = "mod serverpacks";

	private static final byte CMD_REFRESH = 0x01;

	private Logger logger = Logger.getLogger(ServerPacksMod.class.getName());
	private Channel channel = null;

	@Override
	public void init() {
	
		try {
			channel = ModComm.registerChannel("ago.serverpacks", new IChannelListener() {
				@Override
				public void handleMessage(ByteBuffer message) {
					try (PacketReader reader = new PacketReader(message)) {
						int n = reader.readInt();
						while (n-- > 0) {
							String packId = reader.readUTF();
							String uri = reader.readUTF();
							logger.log(Level.INFO, String.format("Got server pack %s (%s)", packId, uri));
							installServerPack(packId, uri);
						}
						refreshModels();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			});
			
			ClassPool classPool = HookManager.getInstance().getClassPool();
			
			String descriptor = Descriptor.ofMethod(classPool.get("com.wurmonline.client.resources.ResourceUrl"), new CtClass[] {
					classPool.get("java.lang.String")
			});
			
			
			HookManager.getInstance().registerHook("com.wurmonline.client.resources.Resources", "findResource", descriptor, new InvocationHandlerFactory() {
				
				@Override
				public InvocationHandler createInvocationHandler() {
					return new InvocationHandler() {
						
						@Override
						public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
							synchronized (proxy) {
								method.setAccessible(true);
								return method.invoke(proxy, args);
							}
						}
					};
				}
			});
			
			
			ModConsole.addConsoleListener(this);
			
		} catch (NotFoundException e) {
			throw new HookException(e);
		}
	}
	

	private void installServerPack(String packId, String packUrl) {
		if (!checkForExistingPack(packId)) {
			downloadPack(packUrl, packId);
		} else {
			enableDownloadedPack(packId);
		}
	}

	private void enableDownloadedPack(String packId) {
		File file = Paths.get("packs", getPackName(packId)).toFile();
		
		if (ModPacks.addPack(file)) {
			logger.log(Level.INFO, "Added server pack " + packId);
		}
	}

	private void downloadPack(String packUrl, String packId) {
		PackDownloader downloader = new PackDownloader(packUrl, packId) {
			@Override
			protected void done(String packId) {
				enableDownloadedPack(packId);
				refreshModels();
			}
		};

		new Thread(downloader).start();
	}

	private boolean checkForExistingPack(String packId) {
		Path path = Paths.get("packs", getPackName(packId));
		if (Files.isRegularFile(path)) {
			return true;
		}
		return false;
	}

	private String getPackName(String packId) {
		return packId + ".jar";
	}

	/**
	 * Send CMD_REFRESH to the server for a complete refresh of all creatures and models
	 */
	private void refreshModels() {
		if (channel != null) {
			try (PacketWriter writer = new PacketWriter()) {
				writer.writeByte(CMD_REFRESH);
				channel.sendMessage(writer.getBytes());
			} catch (IOException e) {
				logger.log(Level.WARNING, e.getMessage(), e);
			}
		}
	}
	
	private void handleConsoleInput(String string) {
		StringTokenizer tokenizer = new StringTokenizer(string, " ");
		if (tokenizer.hasMoreTokens()) {
			String cmd = tokenizer.nextToken();
			switch (cmd.toLowerCase()) {
			case "installpack":
				String id = null;
				String url = null;
				if (tokenizer.hasMoreTokens())
					id = tokenizer.nextToken();
				if (tokenizer.hasMoreTokens())
					url = tokenizer.nextToken();
				if (id != null && url != null)
					installServerPack(id, url);
				else
					printConsoleHelp();
				break;
			case "refresh":
				refreshModels();
				break;
			default:
				printConsoleHelp();
				break;
			}
		} else {
			printConsoleHelp();
		}
		String[] s = string.split(" ", 5);
		if (s.length == 5) {
			installServerPack(s[3], s[4]);
			refreshModels();
		}
		
	}
	
	private void printConsoleHelp() {
		System.out.println("Mod serverpacks console usage:");
		System.out.println("mod serverpacks installpack <packid> <url>");
		System.out.println("	Load a serverpack");
		System.out.println("mod serverpacks refresh");
		System.out.println("	Refresh the models");
	}


	@Override
	public boolean handleInput(String string, Boolean silent) {
		if (string != null && string.startsWith(CONSOLE_PREFIX)) {
			handleConsoleInput(string.substring(CONSOLE_PREFIX.length()).trim());
			return true;
		}
		return false;
	}
}
