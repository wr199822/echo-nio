import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Currency;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * @author wangrui
 * @description
 * @date 2023年01月31日 14:05
 */
public class MultithreadingAsynchronousEcho {
    private static final Pattern QUIT = Pattern.compile("(\\r)?(\\n)?/quit$");

    private static final HashMap<SocketChannel, MultithreadingAsynchronousEcho.Context> contexts = new HashMap<>();

    static ExecutorService executor = Executors.newFixedThreadPool(5);

    public static void main(String[] args) throws IOException {
        executor.submit(()->{
            Selector selector;
            //创建一个
            try {
                selector = Selector.open();
                ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
                serverSocketChannel.bind(new InetSocketAddress(3000));
                serverSocketChannel.configureBlocking(false);
                serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

                while(true){
                    selector.select();
                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while(it.hasNext()){
                        SelectionKey key = it.next();
                        if (key.isAcceptable()){
                            newConnection(selector,key);
                        }else if (key.isReadable()){
                            echo(key);
                        }else if(key.isWritable()){
                            continueEcho(selector,key);
                        }
                    }
                    it.remove();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        });


    }

    private static void newConnection(Selector selector,SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel= (ServerSocketChannel)key.channel();
        SocketChannel socketChannel = serverSocketChannel.accept();
        socketChannel.configureBlocking(false)
                .register(selector,SelectionKey.OP_READ);
        contexts.put(socketChannel,new MultithreadingAsynchronousEcho.Context());

    }

    private static void echo(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        MultithreadingAsynchronousEcho.Context context = contexts.get(socketChannel);
        // executor放在这里  Charset.defaultCharset().decode(context.nioBuffer) 有异常 null占了字节

            try {
                socketChannel.read(context.nioBuffer);
                context.nioBuffer.flip();
                context.currentLine = context.currentLine + Charset.defaultCharset().decode(context.nioBuffer);
                if (QUIT.matcher(context.currentLine).find()) {
                    context.terminating = true;
                } else if (context.currentLine.length() > 16) {
                    context.currentLine = context.currentLine.substring(8);
                }
                executor.submit(()-> {
                    try {
                        //bytebuffer切换为读模式
                        context.nioBuffer.flip();
                        int count = socketChannel.write(context.nioBuffer);
                        if (count < context.nioBuffer.limit()) {
                                key.cancel();
                                socketChannel.register(key.selector(), SelectionKey.OP_WRITE);
                        } else {
                            context.nioBuffer.clear();
                            if (context.terminating) {
                                cleanup(socketChannel);
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            } catch (IOException err) {
                err.printStackTrace();
                try {
                    cleanup(socketChannel);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                executor.shutdown();

            }

    }

    private static void cleanup(SocketChannel socketChannel) throws IOException {
        socketChannel.close();
        contexts.remove(socketChannel);
    }

    private static void continueEcho(Selector selector, SelectionKey key) throws IOException{
        //如果这个方法被触发 说明当前的系统缓冲区有空余
        SocketChannel socketChannel = (SocketChannel) key.channel();
        MultithreadingAsynchronousEcho.Context context = contexts.get(socketChannel);
        try{
            //剩余的字节
            int remainingBytes = context.nioBuffer.limit()- context.nioBuffer.position();
            int count = socketChannel.write(context.nioBuffer);
            if(count==remainingBytes) {
                context.nioBuffer.clear();
                key.cancel();
                if (context.terminating){
                    cleanup(socketChannel);
                }else{
                    socketChannel.register(selector,SelectionKey.OP_READ);
                }
            }
        } catch (IOException err){
            err.printStackTrace();
            cleanup(socketChannel);
        }

    }

    private static class Context{
        private final ByteBuffer nioBuffer = ByteBuffer.allocate(512);
        private String currentLine = "";
        private boolean terminating = false;
    }
}
