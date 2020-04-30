```java
//设定Request的请求类型，为rejoin
DISCOVERY_REJOIN_ACTION_NAME = "internal:discovery/zen/rejoin";

```



ES在启动后是如何建立网络通信机制的。

在ES启动的时候，会首先去实例化Node，在实例化Node的时候，会加载网络模块：NetworkMoudle。

```java
//这里会去加载网络模块
NetworkModule networkModule = new NetworkModule(settings, false, pluginsService.filterPlugins(NetworkPlugin.class),
    threadPool, bigArrays, pageCacheRecycler, circuitBreakerService, namedWriteableRegistry, xContentRegistry,
    networkService, restController);
```



其中需要注意代码：

```java
/**
这个时候回去获取与NetworkPlugin具有相同父类的类
Netty4Plugin是NetworkPlugin的子类，因此会被返回
*/
pluginsService.filterPlugins(NetworkPlugin.class)
```



PluginsService内部维护一个pugins集合，在其进行实例化的时候，会去指定处加载对应的类，并将其放入到plugins集合中。

```java
//触发PluginsService实例化的动作在Node.java中
this.pluginsService = new PluginsService(tmpSettings, environment.configFile(), environment.modulesFile(),environment.pluginsFile(), classpathPlugins);
```



在保持默认的情况下，ES的environment.modulesFile()是{ES_HOME}/module,environment.pluginsFile()={ES_HOME}/plugins

因此在初始化PluginsService的时候，会分别去这两个目录下加载所有的类，并将其放入到plugins集合中

```java
public PluginsService( ... ) {
     ...
     // load modules
     if (modulesDirectory != null) {
             try {
                 Set<Bundle> modules = getModuleBundles(modulesDirectory);
                 for (Bundle bundle : modules) {
                     modulesList.add(bundle.plugin);
                 }
                 seenBundles.addAll(modules);
             } catch (IOException ex) {
                 throw new IllegalStateException("Unable to initialize modules", ex);
             }
         }
	//java中没有二元组，因此构建了一个二元组对象
    List<Tuple<PluginInfo, Plugin>> loaded = loadBundles(seenBundles);
    pluginsLoaded.addAll(loaded);

    //将要加载的类放到plugins中
    this.plugins = Collections.unmodifiableList(pluginsLoaded);
    ...
}
```





再回到上面的实例化NetworkModule部分，基于filter过滤，只会返回NetworkPlugin的子类。这里关注的是Netty4Plugin。

````java
public NetworkModule( ... ) {
    ...
	//执行，实际上会执行Netty4Plugin的getHttpTransports
	Map<String, Supplier<HttpServerTransport>> httpTransportFactory = plugin.getHttpTransports(settings, threadPool, bigArrays,
                pageCacheRecycler, circuitBreakerService, xContentRegistry, networkService, dispatcher);
    ...
}

//终于实例化了Netty4HttpServerTransport
public Map<String, Supplier<HttpServerTransport>> getHttpTransports( ... ) {
        return Collections.singletonMap(NETTY_HTTP_TRANSPORT_NAME,
            () -> new Netty4HttpServerTransport(settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher));
    }
````



实例化Netty4HttpServerTransport后，会调用doStart()方法，注册handler，会后续的http请求添加逻辑处理方法。

```java
protected void doStart() {
	...
	
    serverBootstrap.childHandler(configureServerChannelHandler());
    
    ...
}
```

在看看configureServerChannelHandler

```java
//实例化这个类的时候，会实例化Netty4HttpRequestHandler对象
protected HttpChannelHandler(final Netty4HttpServerTransport transport, final HttpHandlingSettings handlingSettings) {
    this.transport = transport;
    this.handlingSettings = handlingSettings;
    this.requestHandler = new Netty4HttpRequestHandler(transport);
}
```

并在在initChannel()中添加handler

```java
protected void initChannel(Channel ch) throws Exception {
    ...
    ch.pipeline().addLast("handler", requestHandler);
}
```

既然是基于Netty框架，且使用Netty4HttpRequestHandler作为请求处理handler，那么当请求来临之后，就是执行Netty4HttpRequestHandler中的方法channelRead0。经过一系列的调用，会最终执行RestControl中的tryAllHandlers()方法。

```java
//根据url类型，选择对应的handler，执行dispatchRequest
private void tryAllHandlers( ... ) throws Exception {
     ...
    // Resolves the HTTP method and fails if the method is invalid
    requestMethod = request.method();
    
    //从trie树中获取所有的已经注册的handler
    Iterator<MethodHandlers> allHandlers = getAllHandlers(request.params(), rawPath);
    while (allHandlers.hasNext()) {
        final RestHandler handler;
        final MethodHandlers handlers = allHandlers.next();
        if (handlers == null) {
            handler = null;
        } else {
            handler = handlers.getHandler(requestMethod);
        }
        if (handler == null) {
            if (handleNoHandlerFound(rawPath, requestMethod, uri, channel)) {
                return;
            }
        } else {
            dispatchRequest(request, channel, handler);
            return;
        }
    }
    ...
  }


//使用handler去处理请求
private void dispatchRequest(RestRequest request, RestChannel channel, RestHandler handler) throws Exception {
       ...
       handler.handleRequest(request, responseChannel, client);
       ...
}

//最终会执行BaseRestHandler中的handleRequest，而在handleRequest方法中，会调用子类的prepareRequest方法，根据根据handler的不同，会调用不同子类的prepareRequest方法
```

BaseRestHandler是RestHandler的基类。当启动RestXXXAction的时候，在执行构造会进行注册，这里以RestGetAction为例。

```java
//实例化RestGetAction的时候，会对其进行注册，将其存到RestController中
public RestGetAction(final RestController controller) {
    controller.registerHandler(GET, "/{index}/_doc/{id}", this);
    controller.registerHandler(HEAD, "/{index}/_doc/{id}", this);

    // Deprecated typed endpoints.
    controller.registerHandler(GET, "/{index}/{type}/{id}", this);
    controller.registerHandler(HEAD, "/{index}/{type}/{id}", this);
}

//最终会封装成一个handler，保存到trie树中进行保存
public void registerHandler(RestRequest.Method method, String path, RestHandler handler) {
    if (handler instanceof BaseRestHandler) {
        usageService.addRestHandler((BaseRestHandler) handler);
    }
    final RestHandler maybeWrappedHandler = handlerWrapper.apply(handler);
    /**
    * 使用trie树来存储handler
    */
    handlers.insertOrUpdate(path, new MethodHandlers(path, maybeWrappedHandler, method),
                            (mHandlers, newMHandler) -> mHandlers.addMethods(maybeWrappedHandler, method));
}
```

当Rest请求来临的时候，会进过层层转发，最终交由BaseRestHandler的子类来执行prepareRequest方法。

```java
@Override
public RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {
    GetRequest getRequest;
    //在7.x版本，会有type将被移除的警告
    if (request.hasParam("type")) {
        deprecationLogger.deprecatedAndMaybeLog("get_with_types", TYPES_DEPRECATION_MESSAGE);
        getRequest = new GetRequest(request.param("index"), request.param("type"), request.param("id"));
    } else {
        getRequest = new GetRequest(request.param("index"), request.param("id"));
    }

    getRequest.refresh(request.paramAsBoolean("refresh", getRequest.refresh()));
    getRequest.routing(request.param("routing"));
    getRequest.preference(request.param("preference"));
    getRequest.realtime(request.paramAsBoolean("realtime", getRequest.realtime()));
    if (request.param("fields") != null) {
        throw new IllegalArgumentException("the parameter [fields] is no longer supported, " +
                                           "please use [stored_fields] to retrieve stored fields or [_source] to load the field from _source");
    }
    final String fieldsParam = request.param("stored_fields");
    if (fieldsParam != null) {
        final String[] fields = Strings.splitStringByCommaToArray(fieldsParam);
        if (fields != null) {
            getRequest.storedFields(fields);
        }
    }

    getRequest.version(RestActions.parseVersion(request));
    getRequest.versionType(VersionType.fromString(request.param("version_type"), getRequest.versionType()));

    getRequest.fetchSourceContext(FetchSourceContext.parseFromRestRequest(request));

    return channel -> client.get(getRequest, new RestToXContentListener<GetResponse>(channel) {
        @Override
        protected RestStatus getStatus(final GetResponse response) {
            return response.isExists() ? OK : NOT_FOUND;
        }
    });
}

```









`curl -XGET http://localhost:9200`

ES是如何处理GET请求的

启动server，等待接收GET请求，使用handler去处理GET请求，使用`ChannelHandlerContext `去将处理的结果写入到Channel中，传送给请求端。

`Netty4HttpServerTransport`作为接受请求的服务器端，使用在上面注册的`Netty4HttpRequestHandler`,`channelRead0`负责处理发送到服务器端的请求，经过dispatch，找到真正能够处理这个请求的handler，因为发送的是上面的请求，这个最终会由`RestMainAction`来进行最终的请求处理，执行的`prepareRequest`方法。

```java
//执行accept
public RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {
        return channel -> client.execute(MainAction.INSTANCE, new MainRequest(), new RestBuilderListener<MainResponse>(channel) {
            @Override
            public RestResponse buildResponse(MainResponse mainResponse, XContentBuilder builder) throws Exception {
                return convertMainResponse(mainResponse, request, builder);
            }
        });
    }
```

之所以会执行`TransportMainAction`的原因，是因为`TransportMainAction`负责处理`GET /`请求。

```java
public List<Route> routes() {
    return List.of(
    new Route(GET, "/"),
    new Route(HEAD, "/"));
}
```



经过一系列的请求调用，最终执行了`TransportMainAction`的`doExecute`方法

```java
//在这里将要返回的响应封装成MainResponse对象，返回给用户，主要就是调用listener的onResponse方法
protected void doExecute(Task task, MainRequest request, ActionListener<MainResponse> listener) {
        ClusterState clusterState = clusterService.state();
        listener.onResponse(
            new MainResponse(nodeName, Version.CURRENT, clusterState.getClusterName(),
                    clusterState.metadata().clusterUUID(), Build.CURRENT));
    }
```

这里逐次调用`RestActionListener.onResponse()` -> `RestResponseListener.processResponse`

```java
protected final void processResponse(Response response) throws Exception {
        channel.sendResponse(buildResponse(response));
}

//这里的channel是DefaultRestChannel
//最终调用的Netty4HttpChannel，完成reponse数据流的写入工作
public void sendResponse(RestResponse restResponse) {
    ...
    httpChannel.sendResponse(httpResponse, listener);
    ...
}

//Netty4HttpChannel
public void sendResponse(HttpResponse response, ActionListener<Void> listener) {
        channel.writeAndFlush(response, Netty4TcpChannel.addPromise(listener, channel));
    }
```

`RestResponseListener.processResponse`中有一个`buildResponse()方法`，它的职责就是对response进行进行再次包装。`buildResponse()`首先，调用`RestBuilderListener`中的方法

```java
public final RestResponse buildResponse(Response response) throws Exception {
    try (XContentBuilder builder = channel.newBuilder()) {
    final RestResponse restResponse = buildResponse(response, builder);
    assert assertBuilderClosed(builder);
    return restResponse;
    }
}
```

方法在`buildResponse(response, builder)`,在`prepareRequest()`方法中进行了重写，直接调用该方法。



请求端发送GET请求，经过服务端一系列的处理之后，会收到服务器端的响应response，将其解析，展示给用户。如我们基于浏览器发送这个请求，最终在浏览器页面就会返回response的数据结果。