`RestGetAction`负责处理Rest Get请求。

```java
//负责处理形如/{index}/_doc/{id}的请求
public List<Route> routes() {
    return List.of(
    new Route(GET, "/{index}/_doc/{id}"),
    new Route(HEAD, "/{index}/_doc/{id}"));
}
```

如请求`curl -X GET http://localhost:9200/index/_doc/1`获取id为1的文档的内容。

首先因为ES在初始化Node对象的时候，会启动NetworkMoudle模块，并基于`Netty4HttpServerTransport`去启动一个服务端，负责接收http请求。启动实在`doStart()`方法中进行的，实际上就是Netty的那一套东西，需要注意的是，在启动服务端的时候，顺带绑定了处理http请求的handler。

```java
serverBootstrap.childHandler(configureServerChannelHandler());

public ChannelHandler configureServerChannelHandler() {
	return new HttpChannelHandler(this, handlingSettings);
}

//最终实际上handler是HttpChannelHandler内部维护的Netty4HttpRequestHandler
protected HttpChannelHandler(final Netty4HttpServerTransport transport, final HttpHandlingSettings handlingSettings) {
    this.transport = transport;
    this.handlingSettings = handlingSettings;
    this.requestHandler = new Netty4HttpRequestHandler(transport);
}
```

接着就是Netty框架的那一个套路，当请求来临，被服务端接收，交给handler处理，最终实际上是交给handler的`channelRead0`来进行处理。在`Netty4HttpRequestHandler`的`channelRead0()`方法中实际上会对请求进行转发，最终交给匹配其请求的handler来进行处理。

`RestGetAction`实际上是`RestHandler`的子类，因此它也是一个handler，另外上面也说了，负责处理的请求类型。如上请求，正好语气进行匹配，因此会交给它进行处理。选择handler的逻辑代码如下：

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
    
//dispatchRequest
//使用handler去处理请求
private void dispatchRequest(RestRequest request, RestChannel channel, RestHandler handler) throws Exception {
       ...
       handler.handleRequest(request, responseChannel, client);
       ...
}

//对于Rest请求，handler.handleRequest最终都会调用，这个方法再起子类BaseRestHandler中实现
//BaseRestHandler.handleRequest会调用prepareRequest方法
//这个prepareRequest方法统一由各个具体的RestXXXAction来进行实现
public interface RestHandler {
    void handleRequest(RestRequest request, RestChannel channel, NodeClient client) throws Exception;
}
    
//这里是GET请求，因此会调用RestGetAction中的prepareRequest方法
//注意参数NodeClient是final修饰的
public RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {
    return channel -> client.get(getRequest, new RestToXContentListener<GetResponse>(channel) {
            @Override
            protected RestStatus getStatus(final GetResponse response) {
                return response.isExists() ? OK : NOT_FOUND;
            }
        });
}
    
```

注意client是NodeClient，在这里经过一系列的调用，会最终执行NodeClient的

```java
public <    Request extends ActionRequest,
                Response extends ActionResponse
            > Task executeLocally(ActionType<Response> action, Request request, ActionListener<Response> listener) {
        //这里需要说明的是
        return taskManager.registerAndExecute("transport", transportAction(action), request,
            (t, r) -> listener.onResponse(r), (t, e) -> listener.onFailure(e));
    }
```

这个方法，经过一系列的调用，最终会调用`TransportAction`中的`doExecute`,这个方法由其子类实现，子类是`TransportGetAction`,还是一样的道理，我们的请求是GET，因此TransportAction理应也由`TransportGetAction`来完成,，这个方法是其直接继承父类`TransportSingleShardAction`而来的，再在`TransportGetAction`中经过调用，最终会调用`TransportSingleShardAction.start()`方法来进行处理。

```java
public void start() {
    if (shardIt == null) {
    	//这种情况下，数据所在的分片就在当前节点，本地执行
        // just execute it on the local node
        final Writeable.Reader<Response> reader = getResponseReader();
        //不管文档所在的分片是在本地还是在其他节点，都会执行sendRequest
        //这个请求transportService
        transportService.sendRequest(clusterService.localNode(), transportShardAction, 					internalRequest.request(),
                new TransportResponseHandler<Response>() {
                    @Override
                    public Response read(StreamInput in) throws IOException {
                    	return reader.read(in);
                	}

                    @Override
                    public String executor() {
                        return ThreadPool.Names.SAME;
                    }

                    @Override
                        public void handleResponse(final Response response) {
                        listener.onResponse(response);
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        listener.onFailure(exp);
                    }
                });
    } else {
        //数据在其他节点，先将请求发送给对应的节点
    	perform(null);
    }
}
```

请求一旦发送，就会被`ShardTransportHandler`中的内部类`TransportHandler`接收，并交由其方法`messageReceived`进行处理。再经过一系列的调用，最终由`TransportGetAction`中的`shardOperation`来完成具体的任务。

```java
//已经将请求发送到对应的分片，由ES的基本只是可以知道，ES的一个分片，
//实际上就相当于一个Lucene索引，因此到这一步，就是开始执行lucene获取文档操作
Override
protected GetResponse shardOperation(GetRequest request, ShardId shardId) {
    IndexService indexService = indicesService.indexServiceSafe(shardId.getIndex());
    IndexShard indexShard = indexService.getShard(shardId.id());

    if (request.refresh() && !request.realtime()) {
    	indexShard.refresh("refresh_flag_get");
    }
	
    //这就是最终的结果，以GetResult表示
    GetResult result = indexShard.getService().get(request.id(), request.storedFields(),
    	request.realtime(), request.version(), request.versionType(), 		   
    	request.fetchSourceContext());
    //将GetResult封装成一个Response对象，返回
    return new GetResponse(result);
}
```

重点看看`indexShard.getService().get()`方法，这个方法实际上完成了对文档内容的获取，一系列的调用执行了`ShardGetService.innerGet()`方法。

```java
private GetResult innerGet( ... ) {
        
        /**
         * 这里最终要的就是获取了lucene的indexReader对象，他保存在Engine.GetResult内部类中
         */
        Engine.GetResult get = indexShard.get(new Engine.Get(realtime, realtime, id, uidTerm)         			.version(version).versionType(versionType).setIfSeqNo(ifSeqNo).setIfPrimaryTerm(ifPrimaryTerm));
        ...
        // break between having loaded it from translog (so we only have _source), and having a document to load
        /**
        * 基于获取到的IndexReader对象，进行文档的获取，并将其封装成GetResult对象
        * 注意不是Engine内部类GetResult
        */
        return innerGetLoadFromStoredFields(id, gFields, fetchSourceContext, get, mapperService);
            ...
    }
```

一旦获取响应之后，就会调用监听方法执行`onResponse()`，再经过一系列的调用，最终将response数据流写入到Channel中，这又回到了Netty框架那一套。

客户端收到请求之后，解析Response，直接返回数据结果。

