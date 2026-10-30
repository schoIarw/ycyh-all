package com.ycyh.warehouse;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
/** No runtime dependencies: JDK11 HttpServer, read-only repository. */
public final class Warehouse {
 private final Path root;
 public Warehouse(Path root) throws IOException {this.root=root.toRealPath();}
 public static void main(String[] args) throws Exception {
  Path root=Paths.get(args.length>0?args[0]:"./repository");Files.createDirectories(root);
  HttpServer server=new Warehouse(root).start(args.length>1?Integer.parseInt(args[1]):8082);
  Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(1)));
  System.out.println("warehouse listening on "+server.getAddress());
 }
 public HttpServer start(int port) throws IOException {
  HttpServer server=HttpServer.create(new InetSocketAddress(port),32);
  ThreadPoolExecutor pool=new ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"warehouse-http");t.setDaemon(true);return t;},new ThreadPoolExecutor.CallerRunsPolicy());
  server.setExecutor(pool);server.createContext("/",this::handle);server.start();return server;
 }
 private void handle(HttpExchange ex) throws IOException {
  try {
   if(!Set.of("GET","HEAD").contains(ex.getRequestMethod())) {send(ex,405,"text/plain","Method not allowed");return;}
   String path=ex.getRequestURI().getPath();
   if(path.equals("/_catalog")){send(ex,200,"application/json; charset=utf-8",catalog());return;}
   Path file=root.resolve(path.substring(1)).normalize();
   if(!file.startsWith(root)){send(ex,403,"text/plain","Forbidden");return;}
   if(!Files.exists(file)){send(ex,404,"text/plain","Not found");return;}
   file=file.toRealPath();
   if(!file.startsWith(root)){send(ex,403,"text/plain","Forbidden");return;}
   if(Files.isDirectory(file)) {
    StringBuilder html=new StringBuilder("<!doctype html><meta charset='utf-8'><title>YCYH Warehouse</title><h1>组件仓库</h1><ul>");
    try(Stream<Path> children=Files.list(file)) {
     for(Path child:children.sorted().collect(Collectors.toList())) {
      if(Files.isSymbolicLink(child))continue;
      String relative=url(root.relativize(child).toString())+(Files.isDirectory(child)?"/":"");
      html.append("<li><a href=\"/").append(relative).append("\">").append(escape(child.getFileName().toString())).append(Files.isDirectory(child)?"/":"").append("</a></li>");
     }
    }
    send(ex,200,"text/html; charset=utf-8",html.append("</ul>").toString());
   } else if(Files.isRegularFile(file)) {
    ex.getResponseHeaders().set("Content-Type","application/octet-stream");
    ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");
    ex.getResponseHeaders().set("Content-Length",Long.toString(Files.size(file)));
    ex.sendResponseHeaders(200,ex.getRequestMethod().equals("HEAD")?-1:Files.size(file));
    if(!ex.getRequestMethod().equals("HEAD"))Files.copy(file,ex.getResponseBody());
   } else send(ex,403,"text/plain","Forbidden");
  } catch(IllegalArgumentException e){send(ex,400,"text/plain","Bad request");}
    catch(Exception e){try{send(ex,500,"text/plain","Repository unavailable");}catch(IOException ignored){}}
  finally {ex.close();}
 }
 private String catalog() throws Exception {
  List<String> items=new ArrayList<>();
  try(Stream<Path> stream=Files.walk(root,6)) {
   for(Path p:stream.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().endsWith(".tar.gz")).sorted().limit(200).collect(Collectors.toList())) {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=Files.newInputStream(p)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)digest.update(buf,0,n);}
    StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));
    items.add("{\"name\":"+json(root.relativize(p).toString())+",\"path\":"+json(url(root.relativize(p).toString()))+",\"sha256\":"+json(hex.toString())+",\"size\":"+Files.size(p)+"}");
   }
  }
  return "["+String.join(",",items)+"]";
 }
 static String json(String value){StringBuilder b=new StringBuilder("\"");for(char c:value.toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
 static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
 static String url(String value){return Arrays.stream(value.replace('\\','/').split("/")).map(s->URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20")).collect(Collectors.joining("/"));}
 private static void send(HttpExchange ex,int code,String type,String body)throws IOException {
  byte[] bytes=body.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type",type);ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");ex.sendResponseHeaders(code,ex.getRequestMethod().equals("HEAD")?-1:bytes.length);if(!ex.getRequestMethod().equals("HEAD"))ex.getResponseBody().write(bytes);
 }
}