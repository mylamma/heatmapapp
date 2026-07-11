package com.example.heatmap;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 안드로이드 4.2.2(API 17)는 TLSv1.1/1.2를 지원하지만 기본으로 비활성화되어 있어
 * 최신 금융 API(HTTPS) 서버와 통신이 안 되는 경우가 많다.
 * 이 클래스는 소켓 생성 시 TLSv1.1/1.2를 명시적으로 활성화하고,
 * 구형 안드로이드가 자동으로 안 보내는 SNI(Server Name Indication)를 리플렉션으로 강제 설정한다.
 * (SNI가 없으면 여러 도메인을 한 서버군에서 처리하는 곳 - 예: 네이버 - 은 연결을 거부하거나
 * 엉뚱한 인증서를 내려줘서 통신이 실패할 수 있음)
 */
public class TLSSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory internalFactory;

    public TLSSocketFactory() throws KeyManagementException, NoSuchAlgorithmException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        internalFactory = context.getSocketFactory();
    }

    /** MainActivity에서 앱 시작 시 한 번 호출하면 이후 모든 HttpsURLConnection에 적용됨 */
    public static void install() throws KeyManagementException, NoSuchAlgorithmException {
        HttpsURLConnection.setDefaultSSLSocketFactory(new TLSSocketFactory());
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return internalFactory.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return internalFactory.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return enableTLS(internalFactory.createSocket(), null);
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enableTLS(internalFactory.createSocket(s, host, port, autoClose), host);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        return enableTLS(internalFactory.createSocket(host, port), host);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
        return enableTLS(internalFactory.createSocket(host, port, localHost, localPort), host);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enableTLS(internalFactory.createSocket(host, port), null);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return enableTLS(internalFactory.createSocket(address, port, localAddress, localPort), null);
    }

    private Socket enableTLS(Socket socket, String hostname) {
        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;
            sslSocket.setEnabledProtocols(new String[]{"TLSv1.1", "TLSv1.2"});
            if (hostname != null) {
                setHostnameForSni(sslSocket, hostname);
            }
        }
        return socket;
    }

    /**
     * 구형 안드로이드(4.1~4.3 무렵)의 내부 SSL 소켓 구현에는 SNI 호스트명을 지정하는
     * 숨겨진 메서드(setHostname)가 있는데, 표준 API로는 노출되어 있지 않아 리플렉션으로 호출함.
     * 기기/버전에 따라 이 메서드가 없을 수 있어서 실패해도 조용히 무시함 (필수는 아니고 보강 조치).
     */
    private void setHostnameForSni(SSLSocket sslSocket, String hostname) {
        try {
            Method setHostname = sslSocket.getClass().getMethod("setHostname", String.class);
            setHostname.invoke(sslSocket, hostname);
        } catch (Exception ignored) {
            // 이 기기/버전에는 해당 메서드가 없거나 접근 불가 - 무시하고 계속 진행
        }
    }
}
