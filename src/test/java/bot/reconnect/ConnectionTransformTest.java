package bot.reconnect;

import static org.junit.jupiter.api.Assertions.*;

import com.zenith.plugin.bootstrap.SharedApplicationClassLoader;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.utils.FailStrategy;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class ConnectionTransformTest {
  @Test void realZenithConnectionEntrypointsAreBlockedBeforeAuthenticationOrNetworkWork() throws Exception {
    var urls = Arrays.stream(System.getProperty("reconnect.test.classpath").split(Pattern.quote(File.pathSeparator)))
        .map(Path::of).map(path -> {
          try { return path.toUri().toURL(); } catch (Exception failure) { throw new IllegalStateException(failure); }
        }).toArray(URL[]::new);
    try (var loader = new SharedApplicationClassLoader(urls, getClass().getClassLoader())) {
      var manager = new TransformerManager(new BasicClassProvider(loader));
      manager.setFailStrategy(FailStrategy.THROW);
      loader.configureTransformers(manager, () -> fail("Zenith disabled the connection transformers"));
      manager.addTransformer("bot.reconnect.mixin.ProxyConnectMixin");
      manager.addTransformer("bot.reconnect.mixin.ClientDisconnectMixin");
      var gate = loader.loadClass("bot.reconnect.ConnectionGate");
      var listener = loader.loadClass("bot.reconnect.ConnectionGate$Listener");
      var blocked = new AtomicInteger();
      var implementation = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {listener},
          (proxy, method, args) -> {
            if (method.getName().equals("blockConnect")) { blocked.incrementAndGet(); return true; }
            return null;
          });
      gate.getMethod("install", listener).invoke(null, implementation);
      var proxyClass = loader.loadClass("com.zenith.Proxy");
      var proxy = proxyClass.getMethod("getInstance").invoke(null);
      proxyClass.getMethod("connect").invoke(proxy);
      proxyClass.getMethod("connect", String.class, int.class).invoke(proxy, "must-not-connect.invalid", 25565);
      proxyClass.getMethod("connectAndCatchExceptions").invoke(proxy);
      assertEquals(3, blocked.get());
      assertTrue(manager.getTransformedClasses().contains("com.zenith.Proxy"));
    }
  }

  @Test void realZenithSessionCloseIsObservedBeforeItsStateOrEventLoopIsTornDown() throws Exception {
    var provider = new BasicClassProvider(getClass().getClassLoader());
    var manager = new TransformerManager(provider);
    manager.setFailStrategy(FailStrategy.THROW);
    manager.addTransformer("bot.reconnect.mixin.ClientDisconnectMixin");
    byte[] transformed = manager.transform("com.zenith.network.client.ClientSession",
        provider.getClass("com.zenith.network.client.ClientSession"));
    assertNotNull(transformed);
    var node = new ClassNode();
    new ClassReader(transformed).accept(node, 0);
    var closing = node.methods.stream().filter(method -> method.name.equals("callDisconnected")).findFirst().orElseThrow();
    var calls = Arrays.stream(closing.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
        .map(MethodInsnNode.class::cast).toList();
    assertTrue(calls.getFirst().name.contains("fixedReconnect$disconnect"));
    var injected = node.methods.stream().filter(method -> method.name.contains("fixedReconnect$disconnect"))
        .findFirst().orElseThrow();
    assertTrue(Arrays.stream(injected.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
        .map(MethodInsnNode.class::cast)
        .anyMatch(call -> call.owner.equals("bot/reconnect/ConnectionGate") && call.name.equals("beforeDisconnect")));
  }
}
