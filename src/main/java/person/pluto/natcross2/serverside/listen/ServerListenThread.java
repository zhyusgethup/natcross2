package person.pluto.natcross2.serverside.listen;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import person.pluto.natcross2.api.IBelongControl;
import person.pluto.natcross2.api.socketpart.AbsSocketPart;
import person.pluto.natcross2.common.CommonFormat;
import person.pluto.natcross2.executor.NatcrossExecutor;
import person.pluto.natcross2.nio.INioProcesser;
import person.pluto.natcross2.nio.NioHallows;
import person.pluto.natcross2.serverside.listen.clear.IClearInvalidSocketPartThread;
import person.pluto.natcross2.serverside.listen.config.IListenServerConfig;
import person.pluto.natcross2.serverside.listen.control.IControlSocket;
import person.pluto.natcross2.utils.Assert;

/**
 * <p>
 * 监听转发服务进程
 * </p>
 *
 * @author Pluto
 * @since 2019-07-05 10:53:33
 */
@Slf4j
public final class ServerListenThread implements Runnable, INioProcesser, IBelongControl {

	private volatile Thread myThread = null;

	private volatile boolean isAlive = false;
	private volatile boolean canceled = false;

	private final IListenServerConfig config;
	private final ServerSocket listenServerSocket;

	private final Map<String, AbsSocketPart> socketPartMap = new ConcurrentHashMap<>();

	private volatile IControlSocket controlSocket;
	private volatile IClearInvalidSocketPartThread clearInvalidSocketPartThread;

	public ServerListenThread(IListenServerConfig config) throws Exception {
		this.config = config;

		// 此处就开始占用端口，防止重复占用端口，和启动时已被占用
		this.listenServerSocket = this.config.createServerSocket();

		log.info("server listen port[{}] is created!", this.getListenPort());
	}

	@Override
	public void run() {
		while (this.isAlive) {
			try {
				Socket listenSocket = this.listenServerSocket.accept();
				procMethod(listenSocket);
			} catch (Exception e) {
				log.warn("监听服务[" + this.getListenPort() + "]服务异常", e);
				this.cancel();
			}
		}
	}

	@Override
	public void proccess(SelectionKey key) {
		if (!key.isValid()) {
			this.cancel();
		}

		try {
			ServerSocketChannel channel = (ServerSocketChannel) key.channel();
			SocketChannel accept = channel.accept();
			procMethod(accept.socket());
		} catch (IOException e) {
			log.warn("监听服务[" + this.getListenPort() + "]服务异常", e);
			this.cancel();
		}
	}

	private void procMethod(Socket listenSocket) {
		NatcrossExecutor.executeServerListenAccept(() -> {
			// 如果没有控制接收socket，则取消接入，不主动关闭所有接口，防止controlSocket临时掉线，讲道理没有controlSocket也不会启动
			if (Objects.isNull(this.controlSocket)) {
				try {
					listenSocket.close();
				} catch (IOException e) {
					// do nothing
				}
				return;
			}

			String socketPartKey = CommonFormat.getSocketPartKey(this.getListenPort());

			AbsSocketPart socketPart = this.config.newSocketPart(ServerListenThread.this);
			socketPart.setSocketPartKey(socketPartKey);
			socketPart.setRecvSocket(listenSocket);

			this.socketPartMap.put(socketPartKey, socketPart);
			// 发送指令失败，同controlSocket为空，不使用异步执行，毕竟接口发送只能顺序，异步的方式也会被锁，等同同步
			if (!sendClientWait(socketPartKey)) {
				this.socketPartMap.remove(socketPartKey);
				socketPart.cancel();
				return;
			}
		});
	}

	/**
	 ** 告知客户端，有新连接
	 *
	 * @author Pluto
	 * @since 2019-07-11 15:45:14
	 * @param socketPartKey
	 */
	private boolean sendClientWait(String socketPartKey) {
		log.info("告知新连接 sendClientWait[{}]", socketPartKey);
		boolean sendClientWait = false;

		try {
			sendClientWait = this.controlSocket.sendClientWait(socketPartKey);
		} catch (Exception e) {
			log.error("告知新连接 sendClientWait[" + socketPartKey + "] 发生未知异常", e);
			sendClientWait = false;
		}

		if (!sendClientWait) {
			log.warn("告知新连接 sendClientWait[" + socketPartKey + "] 失败");
			if (this.controlSocket == null || !this.controlSocket.isValid()) {
				// 保证control为置空状态
				stopListen();
			}
			return false;
		}
		return true;
	}

	/**
	 ** 启动
	 *
	 * @author Pluto
	 * @throws Exception
	 * @since 2020-01-07 09:36:30
	 */
	public void start() {
		Assert.state(this.canceled == false, "已退出，不得重新启动");

		this.isAlive = true;

		log.info("server listen port[{}] starting ...", this.getListenPort());

		if (this.clearInvalidSocketPartThread == null) {
			this.clearInvalidSocketPartThread = this.config.newClearInvalidSocketPartThread(this);
			if (this.clearInvalidSocketPartThread != null) {
				this.clearInvalidSocketPartThread.start();
			}
		}

		ServerSocketChannel channel = this.listenServerSocket.getChannel();
		if (Objects.nonNull(channel)) {
			if (!channel.isRegistered() || (channel.validOps() & SelectionKey.OP_ACCEPT) == 0) {
				try {
					NioHallows.register(channel, SelectionKey.OP_ACCEPT, this);
				} catch (IOException e) {
					log.error("register serverListen channel[{}] faild!", config.getListenPort());
					this.cancel();
					throw new RuntimeException("nio注册时异常", e);
				}

			}
		} else {
			if (this.myThread == null || !this.myThread.isAlive()) {
				this.myThread = new Thread(this);
				this.myThread.setName("server-listen-" + this.formatInfo());
				this.myThread.start();
			}
		}

		log.info("server listen port[{}] start success!", this.getListenPort());
	}

	/**
	 ** 关停监听服务，不注销已经建立的，并置空controlSocket
	 *
	 * @author Pluto
	 * @since 2019-07-18 18:43:43
	 */
	public void stopListen() {
		log.info("stopListen[{}]", this.getListenPort());
		this.isAlive = false;

		IControlSocket controlSocket = this.controlSocket;
		if ((controlSocket = this.controlSocket) != null) {
			this.controlSocket = null;
			try {
				controlSocket.close();
			} catch (Exception e) {
				log.debug("监听服务控制端口关闭异常", e);
			}
		}

		Thread myThread;
		if ((myThread = this.myThread) != null) {
			this.myThread = null;
			myThread.interrupt();
		}
	}

	/**
	 ** 退出
	 *
	 * @author Pluto
	 * @since 2020-01-07 09:37:00
	 */
	public void cancel() {
		if (this.canceled) {
			return;
		}
		this.canceled = true;
		this.stopListen();

		ListenServerControl.remove(this.getListenPort());

		log.info("serverListen cancelling[{}]", this.getListenPort());

		try {
			this.listenServerSocket.close();
		} catch (Exception e) {
			// do no thing
		}

		IClearInvalidSocketPartThread clearInvalidSocketPartThread = this.clearInvalidSocketPartThread;
		if ((clearInvalidSocketPartThread = this.clearInvalidSocketPartThread) != null) {
			this.clearInvalidSocketPartThread = null;
			try {
				clearInvalidSocketPartThread.cancel();
			} catch (Exception e) {
				// do no thing
			}
		}

		String[] array = socketPartMap.keySet().toArray(new String[0]);

		for (String key : array) {
			stopSocketPart(key);
		}

		log.debug("serverListen cancel[{}] is success", this.getListenPort());
	}

	/**
	 ** 停止指定的端口
	 *
	 * @author Pluto
	 * @since 2019-07-11 16:33:10
	 * @param socketPartKey
	 * @return
	 */
	@Override
	public boolean stopSocketPart(String socketPartKey) {
		log.debug("停止接口 stopSocketPart[{}]", socketPartKey);
		AbsSocketPart socketPart = this.socketPartMap.remove(socketPartKey);
		if (socketPart == null) {
			return false;
		}
		socketPart.cancel();
		return true;
	}

	/**
	 ** 清理无效socketPart
	 *
	 * @author Pluto
	 * @since 2019-08-21 12:50:57
	 */
	public void clearInvaildSocketPart() {
		log.debug("clearInvaildSocketPart[{}]", this.getListenPort());

		Set<String> keySet = socketPartMap.keySet();
		// 被去除的时候set会变化而导致空值问题
		String[] array = keySet.toArray(new String[0]);

		for (String key : array) {

			AbsSocketPart socketPart = this.socketPartMap.get(key);
			if (socketPart != null && !socketPart.isValid()) {
				stopSocketPart(key);
			}
		}

	}

	/**
	 ** 将接受到的连接进行设置组合
	 * 
	 * @param socketPartKey
	 * @param sendSocket
	 * @return
	 */
	public boolean doSetPartClient(String socketPartKey, Socket sendSocket) {
		log.debug("接入接口 doSetPartClient[{}]", socketPartKey);
		AbsSocketPart socketPart = this.socketPartMap.get(socketPartKey);
		if (socketPart == null) {
			return false;
		}
		socketPart.setSendSocket(sendSocket);

		boolean createPassWay = socketPart.createPassWay();
		if (!createPassWay) {
			socketPart.cancel();
			stopSocketPart(socketPartKey);
			return false;
		}

		return true;
	}

	/**
	 ** 设置控制端口
	 *
	 * @author Pluto
	 * @since 2019-07-18 18:46:05
	 * @param controlSocket
	 */
	public void setControlSocket(Socket socket) {
		log.info("setControlSocket[{}]", this.getListenPort());

		IControlSocket controlSocket = this.config.newControlSocket(socket, null);

		if (this.controlSocket != null) {
			try {
				this.controlSocket.close();
			} catch (Exception e) {
				log.debug("监听服务控制端口关闭异常", e);
			}
			this.controlSocket = null;
		}

		controlSocket.setServerListen(this);
		controlSocket.startRecv();
		this.controlSocket = controlSocket;
		this.start();
	}

	/**
	 ** 获取监听端口
	 *
	 * @author Pluto
	 * @since 2019-07-18 18:45:57
	 * @return
	 */
	public Integer getListenPort() {
		return this.config.getListenPort();
	}

	/**
	 ** 获取已建立的连接
	 *
	 * @author Pluto
	 * @since 2019-07-19 16:32:09
	 * @return
	 */
	public List<String> getSocketPartList() {
		return new LinkedList<>(this.socketPartMap.keySet());
	}

	/**
	 ** 获取socket对
	 *
	 * @author Pluto
	 * @since 2020-01-07 09:43:08
	 * @return
	 */
	public Map<String, AbsSocketPart> getSocketPartMap() {
		return this.socketPartMap;
	}

	/**
	 ** 是否激活状态
	 *
	 * @author Pluto
	 * @since 2020-01-07 09:43:28
	 * @return
	 */
	public boolean isAlive() {
		return this.isAlive;
	}

	/**
	 * 是否已退出
	 *
	 * @return
	 * @author Pluto
	 * @since 2021-04-13 13:39:11
	 */
	public boolean isCanceled() {
		return this.canceled;
	}

	/**
	 * 获取配置
	 * 
	 * @author Pluto
	 * @since 2020-01-08 16:55:41
	 * @return
	 */
	public IListenServerConfig getConfig() {
		return this.config;
	}

	public String formatInfo() {
		return String.valueOf(this.getListenPort());
	}

}
