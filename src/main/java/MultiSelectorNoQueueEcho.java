import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Pattern;

/**
 * @author wangrui
 * @description
 * @date 2023年02月07日 20:31
 */
public class MultiSelectorNoQueueEcho {

    private static final Pattern QUIT = Pattern.compile("(\\r)?(\\n)?/quit$");

    private static final HashMap<SocketChannel, MultiSelectorNoQueueEcho.Context> contexts = new HashMap<>();

    public static void main(String[] args) throws IOException {
        //创建一个
        Selector boss = Selector.open();
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(3000));
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(boss, SelectionKey.OP_ACCEPT);
        Selector worker = Selector.open();
        while(true){
            //select 方法的作用是什么？
            //select的作用就是把准备好的channel 返回过来
            boss.select();
            //selectionKey 的作用是什么。
            //这个Iterator<SelectionKey> 其实就是那些准备好的channel 所感兴趣的SelectionKey 然后通过SelectionKey获取目标channel
            Iterator<SelectionKey> it = boss.selectedKeys().iterator();
            while(it.hasNext()){
                SelectionKey key = it.next();
                if (key.isAcceptable()){
                    newConnection(worker,key);
                }
                it.remove();
            }

        }
    }

    private static void newConnection(Selector worker, SelectionKey key) throws IOException {
        //拿到连接的channel
        ServerSocketChannel serverSocketChannel= (ServerSocketChannel)key.channel();
        // 为什么要创建一个socketChannel
        SocketChannel sc = serverSocketChannel.accept();
        sc.configureBlocking(false);
        sc.register(worker,SelectionKey.OP_READ);
        new Thread(()->{
            while(true){
                try{
                    worker.select();
                    Iterator<SelectionKey> iter = worker.selectedKeys().iterator();
                    while(iter.hasNext()){
                        SelectionKey workerKey = iter.next();
                        if(workerKey.isReadable()){
                            echo(workerKey);
                        }else if(workerKey.isWritable()){
                            continueEcho(worker,workerKey);
                        }
                        iter.remove();
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
        contexts.put(sc,new MultiSelectorNoQueueEcho.Context());
    }

    private static void echo(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        MultiSelectorNoQueueEcho.Context context = contexts.get(socketChannel);
        try {
            //将socketChannel中的数据读取到context中
            socketChannel.read(context.nioBuffer);
            //bytebuffer切换为读模式
            context.nioBuffer.flip();
            //currentLine的作用是什么？
            //使用currentLine的目的是  防止nioBuffer一次写入过多 而导致(\r\t/quit)这个字符串分两次接送了而无法停止
            context.currentLine = context.currentLine + Charset.defaultCharset().decode(context.nioBuffer);
            if (QUIT.matcher(context.currentLine).find()) {
                context.terminating = true;
            } else if (context.currentLine.length() > 16) {
                //目的是为了节省资源 因为(\r\t/quit)最长也就八个字节
                context.currentLine = context.currentLine.substring(8);
            }
            //bytebuffer切换为读模式
            context.nioBuffer.flip();
            //这一步就已经回写给了客户端
            int count = socketChannel.write(context.nioBuffer);
            if (count < context.nioBuffer.limit()) {
                //如果socketChannel一次并没有把context.nioBuffer读完 说明 系统缓存区满了
                //那么需要将socketChannel设置去关心写事件 将niobuffer剩余的数据写完在关心读事件
                key.cancel();  //这个方法应该是把这个selector和channel解绑
                //然后重新绑定selector和这个channel 并关心写事件
                socketChannel.register(key.selector(), SelectionKey.OP_WRITE);
            } else {
                //切换成写模式 重置相关指针
                context.nioBuffer.clear();
                if (context.terminating) {
                    cleanup(socketChannel);
                }
            }
        }catch (IOException err){
            err.printStackTrace();
            cleanup(socketChannel);
        }

    }

    private static void continueEcho(Selector selector,SelectionKey key) throws IOException{
        //如果这个方法被触发 说明当前的系统缓冲区有空余
        SocketChannel socketChannel = (SocketChannel) key.channel();
        MultiSelectorNoQueueEcho.Context context = contexts.get(socketChannel);
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
                    socketChannel.register(selector, SelectionKey.OP_READ);
                }
            }
        } catch (IOException err){
            err.printStackTrace();
            cleanup(socketChannel);
        }

    }

    private static void cleanup(SocketChannel socketChannel) throws IOException{
        socketChannel.close();
        contexts.remove(socketChannel);
    }


    private static class Context{
        private final ByteBuffer nioBuffer = ByteBuffer.allocate(512);
        private String currentLine = "";
        private boolean terminating = false;
    }
}
