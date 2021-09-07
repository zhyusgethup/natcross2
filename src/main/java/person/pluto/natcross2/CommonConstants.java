package person.pluto.natcross2;

/**
 * <p>
 * 公共参数
 * </p>
 *
 * @author Pluto
 * @since 2020-04-10 12:29:01
 */
public final class CommonConstants {

	// 服务端地址，支持IP或域名，这个根据服务端放的网络位置进行设置
	public static final String serviceIp = "120.79.186.158";
	// 客户端服务的端口
	public static final int servicePort = 10010;

	// 映射对
	public static ListenDest[] listenDestArray = new ListenDest[] {
			//zk
			ListenDest.of(2181, "127.0.0.1", 2181),
			ListenDest.of(2888, "127.0.0.1", 2888),
			ListenDest.of(3888, "127.0.0.1", 3888),
			//zkui
			ListenDest.of(9090, "127.0.0.1", 9090),
			//mysql
			ListenDest.of(3306, "127.0.0.1", 3306),
			//redis
			ListenDest.of(6379, "127.0.0.1", 6379),
			//gateway
			ListenDest.of(7000, "127.0.0.1", 7000),
			//auth
			ListenDest.of(7001, "127.0.0.1", 7001),
			//blog
			ListenDest.of(7002, "127.0.0.1", 7002),
	};

	// 交互密钥 AES
	public static final String aesKey = "8AUWlb+IWD+Fhbs0xnXCCg==";
	// 交互签名key
	public static final String tokenKey = "tokenKey";

	/**
	 * <p>
	 * 监听、映射对
	 * </p>
	 */
	static class ListenDest {
		public static ListenDest of(int listenPort, String destIp, int destPort) {
			ListenDest model = new ListenDest();
			model.listenPort = listenPort;
			model.destIp = destIp;
			model.destPort = destPort;
			return model;
		}

		// 服务端监听的端口，外网访问服务端IP:listengPort即可进行穿透
		public int listenPort;
		// 穿透的目标，即要暴露在外网的内网IP
		public String destIp;
		// 要暴露的内网端口
		public int destPort;
	}

}
