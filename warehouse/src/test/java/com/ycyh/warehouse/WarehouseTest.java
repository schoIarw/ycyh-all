package com.ycyh.warehouse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
class WarehouseTest {
 @TempDir Path dir;
 HttpServer server;String base;
 @BeforeEach void start()throws Exception{
  Path root=Files.createDirectory(dir.resolve("repo"));Files.writeString(root.resolve("elasticsearch-8.17.0-linux-x86_64.tar.gz"),"archive-content");Files.writeString(root.resolve("<x>.txt"),"x");Files.writeString(dir.resolve("secret"),"secret");Files.createSymbolicLink(root.resolve("outside"),dir.resolve("secret"));
  server=new Warehouse(root).start(0);base="http://127.0.0.1:"+server.getAddress().getPort();
 }
 @AfterEach void stop(){server.stop(0);}
 HttpResponse<String> get(String path)throws Exception{return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+path)).build(),HttpResponse.BodyHandlers.ofString());}
 @Test void listsDownloadsAndCatalogsWithDigest()throws Exception{
  String listing=get("/").body();assertTrue(listing.contains("&lt;x&gt;.txt"));assertTrue(listing.contains("%3Cx%3E.txt"));assertFalse(listing.contains("outside"));
  assertEquals("archive-content",get("/elasticsearch-8.17.0-linux-x86_64.tar.gz").body());String cat=get("/_catalog").body();assertTrue(cat.contains("sha256"));assertTrue(cat.contains("elasticsearch-8.17.0"));
  HttpResponse<Void> head=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/elasticsearch-8.17.0-linux-x86_64.tar.gz")).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());assertEquals("15",head.headers().firstValue("content-length").orElse(""));
 }
 @Test void rejectsTraversalAndSymlinkEscapes()throws Exception{assertEquals(403,get("/../secret").statusCode());assertEquals(403,get("/%2e%2e/secret").statusCode());assertEquals(403,get("/outside").statusCode());assertEquals(404,get("/missing").statusCode());}
}
