ES写入实际上是Bulk操作，因此会由`RestBulkAction`来负责

```java
ublic List<Route> routes() {
    return List.of(
        new Route(POST, "/_bulk"),
        new Route(PUT, "/_bulk"),
        new Route(POST, "/{index}/_bulk"),
        new Route(PUT, "/{index}/_bulk"));
}
```

和Get请求一样，当往ES中写入文档时，最终会由`RestBulkAction`中的`prepareRequest()`方法来进行处理，和GET请求一样，经过一系列的调用，也会最终执行`NodeClient`中的`executeLocally()`方法

```java
public <Request extends ActionRequest,Response extends ActionResponse> Task executeLocally(ActionType<Response> action, Request request, TaskListener<Response> listener) {
        return taskManager.registerAndExecute("transport", transportAction(action), request,listener::onResponse, listener::onFailure);
}
```

这个方法还是会经过一系列的调用，最终执行`TransportAction`的`doExecute()`方法，这个方法是由子类实现的，由于这里是Bulk操作，因此会执行`TransportBulkAction`中的`doExecute()`，它的这个方法是在父类`TransportReplicationAction`中实现的。

先不看`doExecute`方法的具体实现，先看看`TransportReplicationAction`的构造方法。

```java
protected TransportReplicationAction( ... TransportService transportService ...) {
      ...
      /**
      注册handler，当主分片有写请求的时候，使用handlePrimaryRequest来进行处理
      **/
      transportService.registerRequestHandler(transportPrimaryAction, executor,
      	forceExecutionOnPrimary, true,in -> new ConcreteShardRequest<>(requestReader, in),
      	this::handlePrimaryRequest);

     /**
     注册handler，当副本分片有写请求的时候，使用handleReplicaRequest来进行处理
     **/
     transportService.registerRequestHandler(transportReplicaAction, executor, true, true,
     	in -> new ConcreteReplicaRequest<>(replicaRequestReader, in),
     	this::handleReplicaRequest);
    }
```

现在再来看`doExecute`方法

```java
@Override
protected void doExecute(Task task, Request request, ActionListener<Response> listener) {
    assert request.shardId() != null : "request shardId must be set";
    new ReroutePhase((ReplicationTask) task, request, listener).run();
}
```

这里通过`run()`方法会调用到`ReroutePhase`中的`doRun()`方法，在这个方法里头开始获取primary分片的信息，为往primary分片中写数据做准备。

```java
@Override
protected void doRun() {
    /**
    * 获取主分片信息
    */
    final ShardRouting primary = state.getRoutingTable().shardRoutingTable(request.shardId()).primaryShard();

    /**
    * 获取主分片所在的节点信息
    */
    final DiscoveryNode node = state.nodes().get(primary.currentNodeId());

    if (primary.currentNodeId().equals(state.nodes().getLocalNodeId())) {
        /**
        * 点前节点就是主分片所在的节点
        */
        performLocalAction(state, primary, node, indexMetadata);
    } else {
        /**
        * 主分片在其他节点上
        */
        performRemoteAction(state, primary, node);
    }
}
```

在通过`performLocalAction()`方法，最终会执行`performAction()`方法。

```java
/**
此方法会由performLocalAction调用，在进行调用的时候传入的参数是transportPrimaryAction
该参数的值为：p，因此请求会被一个能够处理primary的handler接收，这里就是本类中的handlePrimary**方法
**/
private void performAction(final DiscoveryNode node, final String action, final boolean isPrimaryAction,final TransportRequest requestToPerform) {
    transportService.sendRequest(node, action, requestToPerform, transportOptions, new TransportResponseHandler<Response>(){}
 }
```

在这里基于`transportService`发送request请求，该请求是往primary分片中写入数据，前面已经注册了handler：`handlePrimaryRequest`用于处理primary分片的写入请求。因此这个请求会被`handlePrimaryRequest`方法处理.

```java
protected void handlePrimaryRequest(final ConcreteShardRequest<Request> request, final TransportChannel channel, final Task task) {
        new AsyncPrimaryAction(
            request, new ChannelActionListener<>(channel, transportPrimaryAction, request), (ReplicationTask) task).run();
    }
```

通过这个`run`方法，会调用`AsyncPrimaryAction.doRun`方法，最终会调用``AsyncPrimaryAction.runWithPrimaryShardReference`方法

```java
void runWithPrimaryShardReference(final PrimaryShardReference primaryShardReference) {
    /**
    * 这里会执行ReplicationOperation.execute方法，进行真正的主分片写入操作
    */
    new ReplicationOperation<>(primaryRequest.getRequest(), primaryShardReference,   
                               ActionListener.map(responseListener,                     
                               result -> result.finalResponseIfSuccessful),             
                               newReplicasProxy(), logger, actionName,
                               primaryRequest.getPrimaryTerm())
        .execute();
}
```

在这里又会去调用`ReplicationOperation`中的`execute`方法。

```java
public void execute() throws Exception {
    /**
    * 这一步非常关键，在执行完主分片的写入之后，会执行handlePrimaryResult方法，实现对副本分片的写入操作
    */
    primary.perform(request,
                ActionListener.wrap(this::handlePrimaryResult,resultListener::onFailure));
}
```

从这里可以看出来，主分片的写入和副本分片的写入是同步进行的。先写入主分片，写入成功后，基于监听器回调`handlePrimaryResult`方法，开始副本分片的数据写入过程，当主分片和副本分片均写入成功之后，才会给Client返回结果。**这种写入模式是否会存在单点写入速度慢从而拖累整个写入过程？？？**





