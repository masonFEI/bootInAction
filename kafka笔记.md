进程间的数据交互中间件-消息中间件

## 数据保存

kafka的消息数据存储在磁盘上

## 数据流

消费端从 kafka 服务器**拉取**消息（pull，长轮询）；生产端向 kafka 服务器**发送**消息；
生产端是一批一批攒着发（batch.size / linger.ms，一个 batch 只能属于一个 partition）；
kafka 本身把消息**持久化到本地磁盘**（segment 文件，按 retention 时间/大小清理）

- offset：消费位点，记录每个 partition 读到哪了，支持断点续传
- 分区：并行度单位，一个 partition 同一时刻只被组内一个消费者消费
- 副本：leader 读写（选取出来主节点）、follower 复制，ISR 决定可用性