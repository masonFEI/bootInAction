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

## 消费堆积（lag 过大）的排查与治理

### 先定位：是「单分区慢」还是「整体都慢」

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group>
```

输出列：`TOPIC  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID`

| 现象 | 结论 | 处理方向 |
| --- | --- | --- |
| 所有分区 LAG 都大 | 消费端整体能力不足 | 扩机器 + 批量化 + 优化消费逻辑 |
| 只有一个分区 LAG 大 | 见下方三种成因 | 针对性处理 |

单分区堆积的三种成因：

1. **分配不均**：组内消费者实例数 ≥ 分区数，多余实例空转；或实例频繁重启导致 rebalance 后分配倾斜
2. **消息本身特殊**：该分区有超大消息、热点 key 全落进来、个别消息处理特别慢
3. **消费者卡住**：某条消息抛异常且未被正确处理 → offset 不提交 → 无限重试同一批

### 两个硬约束（为什么不能简单粗暴地加线程）

1. **消费并行度上限 = 分区数**
   一个 partition 同一时刻只被组内**一个** consumer 消费，所以加消费者实例最多加到分区数，再多就是空转。

2. **单分区内必须按 offset 顺序消费**
   「一个分区开 N 个线程无序处理」会破坏顺序。Kafka 2.4+ 的 KIP-429 支持同分区多线程，但要求按 offset 切成**连续区间**分配给各线程，且 offset 提交只能推进到「连续完成的水位线」，实现复杂度高。

3. **offset 提交时机决定语义**

   | 写法 | 后果 |
   | --- | --- |
   | 先提交 offset，再处理 | 宕机丢消息 |
   | 先处理，再提交 offset | 重复消费（at-least-once，幂等是业务责任） |
   | `enable.auto.commit=true` | 消费过程中就提交，同样有丢消息风险 |

### 应急止血（代价由低到高）

**① 批量消费 + 批量写库**（首选，无架构改动）

```conf
max.poll.records=5000        # 单次拉取条数，默认 500
max.poll.interval.ms=300000  # 两次 poll 的最大间隔，防止被踢出组
```

业务侧改攒批：poll 一批 → 攒够 N 条或等 T 毫秒 → 批量 upsert → 一次性提交 offset。

**② 跳过毒丸消息（死信队列）**

```java
try {
    handle(record);
} catch (Exception e) {
    log.error("处理失败，转入死信队列", e);
    deadQueue.send(record.value());   // 关键：不能让它堵住整条分区
}
// 无论成败，都在 handle 完成后提交 offset
```

**③ 加消费者实例**（前提：消费者数 < 分区数）

```bash
kafka-topics.sh --describe --topic <topic>   # 先确认分区数
```

消费者数已经 ≥ 分区数时，加实例**完全无效**——多余的 consumer 在 rebalance 后拿到空分区。

**④ 消费端内部并发（保序提交）**

poll 出来 → 放入有序队列 → 工作线程并发处理 → **按 offset 连续水位线提交**（只有连续完成的部分才推进提交位点）。
代价是实现复杂度上升，下游写入顺序敏感时要谨慎。

**⑤ 扩分区**（有硬伤，慎用）

```bash
kafka-topics.sh --alter --topic <topic> --partitions <newCount>
```

代价：

- **破坏 key 顺序**：同一个 key 的 hash 会落到不同分区，原本「同 key 有序」的保证失效
- **只对新消息生效**：存量积压数据仍留在老分区，不会被重新分配
- 扩容过程触发 rebalance
- **分区只能增不能减**，扩错了只能新建 topic 迁移

所以扩分区是**止血手段，不是长期方案**。

**⑥ 重置 offset 丢弃积压**（最后手段，需业务方拍板）

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group <g> --topic <t> --reset-offsets --to-latest --execute
```

数据直接丢失，只适用于「积压的是可丢弃数据」（埋点、监控日志、可重算的派生数据）。

### 根治方案

**消费端改造**

| 维度 | 易堆积的写法 | 改造后 |
| --- | --- | --- |
| 拉取 | 单条处理 | 攒批处理（100ms 或 500 条触发一次） |
| 写库 | 单条 insert | 批量 insert / MyBatis foreach / JDBC batch |
| 写入语义 | insert | upsert（保证幂等，重复消费不炸） |
| I/O | 同步阻塞 | 异步化 / 缓冲 |
| 外部依赖 | 同步调用下游 | 降级为投递到本地队列或 MQ |

> 关键前提：**处理逻辑必须做成幂等的**，因为 at-least-once 一定会重复。

**分区数规划**

- 分区数 ≥ 预期峰值吞吐 ÷ 单分区吞吐
- **预留 2~3 倍余量**，因为分区只能增不能减
- 提前在 broker 端监控单分区流量，别等堆积了才扩

**热点分区治理**

如果单分区堆积是因为某个热点 key 全落进了同一个分区：

- 生产端**不能**随便改 key（会破坏该 key 的顺序）
- 正确做法：把热点 key **拆到独立 topic**，用更高优先级/更多资源的消费组处理
- 或用 Kafka Streams 做 repartition 中间 topic，把热点 key 再散开

**监控告警**

```bash
# 消费延迟（秒）比 lag 条数更有意义
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group>
```

- lag 告警：设阈值（如 10 万条，或消费延迟 > 5 分钟）
- 消费端埋点：单条处理耗时 P99、失败率、批次大小
- 堆积后要能**快速定位到分区**，而不是只看总量

### 排查命令速查

```bash
# 1. 看 lag 分布
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group>

# 2. 看各分区 end offset
kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic <topic> --time -1

# 3. 看分区数
kafka-topics.sh --describe --topic <topic>

# 4. 看组成员和分配状态
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group> --members
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group> --state

# 5. 重置 offset（慎用）
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group <g> --topic <t> --reset-offsets --to-latest --execute
```
