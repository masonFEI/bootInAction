索引是帮助mysql高效获取数据的排好序的数据结构

B+Tree

- 非叶子节点不存储data，只存储索引，可以放更多的索引
- 叶子节点包含所有索引字段
- 叶子节点用指针俩姐，提高区间访问的性能

innodb索引，使用这种索引的表一般要建个主键

explain，查看执行计划

索引最左前缀原则

# 表关联

禁止超过三张表关联查询

# 内部核心组件

词法分析，语法分析 --> 优化器

## 一条sql是的底层是如何执行的？

整体链路：客户端 -> 连接器 -> 解析器(词法、语法、语义) -> 优化器 -> 执行器 -> 存储引擎(InnoDB) -> 组装结果返回

- 连接器：TCP握手，认证账号密码，校验权限，分配线程(连接)；后续每条SQL都复用这条连接
- 解析器：词法分析切token -> 语法分析生成解析树 -> 语义分析检查表名、列名是否存在
- 优化器：基于统计信息估算行数，比较各执行计划成本，选索引、选join顺序、决定是否索引下推，生成执行计划
- 执行器：按执行计划逐层调用存储引擎的handler接口取数据；没走上索引就是全表扫描
- 存储引擎(InnoDB)：
    - 走B+Tree定位数据页，先读buffer pool，命中直接返回，未命中读磁盘并回填缓存
    - 可见性判断：undo log串起版本链，配合read view判断该版本对当前事务是否可见
    - 写数据：先写redo log(先写日志再改数据，WAL)，再写binlog，最后改内存里的数据页
- 提交：redo log prepare -> 写binlog -> redo log commit，两阶段提交保证两个日志一致
- 返回：Server层把结果集缓存好，经网络写回客户端，由客户端解析展示

使用mysql自带的命令行工具查看bin.log

## 为什么有了binlog，还需要redo log?

两者层级和用途都不同，不是重复关系：

- binlog：MySQL Server层的逻辑日志，记录"做了什么"(ROW格式下是行的增删改事件)，所有存储引擎共用
    - 用途：主从复制(从库重放)、增量备份、基于时间点恢复(PITR)、审计
    - 特点：追加写，按文件轮转；事务提交时才刷盘(写文件、fsync、binlog cache)
- redo log：InnoDB存储引擎的物理日志，记录"某个数据页怎么改的"，只有InnoDB有
    - 用途：崩溃恢复(crash recovery)、事务持久性(ACID的D)
    - 特点：固定大小循环写(ib_logfile)，checkpoint推进后被覆盖，只保留最近未刷盘的修改

为什么不能互相替代：

- 只有redo log：物理日志且是本机循环覆盖的，既没法给从库复制，也没法做时间点恢复和长期归档，主从复制必须靠binlog
- 只有binlog：binlog是Server层的逻辑日志，InnoDB崩溃后内存中未落盘的脏页没法靠它把数据页还原回来
  (它记的是"做了什么"而不是"页的物理状态")，数据一致性保证不了
- 两者配合：redo log保证本机崩溃能恢复，binlog保证跨实例复制和时间点恢复；提交时用XA两阶段提交(redo prepare -> 写binlog ->
  redo commit)保证同一事务在两个日志中一致，避免主从或恢复时"一边有一边没有"

指定binlog位置，执行恢复命令,mysql会重新一次执行下

# 事务

MVCC 多版本并发控制，就可以做到读写不阻塞，且避免了类似脏读这样的问题，主要通过undo日志来实现

- undo log：记录每次修改前的旧值，既用于回滚(原子性)，也用来串起版本链
- read view：事务读数据时的快照视角(活跃事务列表+可见版本)，决定这次读能看到哪个版本
- 快照读：普通SELECT，按read view沿版本链找可见版本，不加行锁，不阻塞别人写
- 当前读：UPDATE、DELETE、SELECT ... FOR UPDATE，读最新版本并加行锁

事务的ACID特性：

- 原子性：由undo log 来保证
- 一致性：事务处理的最终结果
- 隔离性：
    - 读未提交(ru)：会脏读
    - 读已提交(rc)：不会脏读，但会不可重复读
    - 可重复读(rr，mysql默认的)：不会脏读、不会不可重复读，但会幻读(InnoDB靠间隙锁大部分解决)
    - 串行：上述问题全部避免
- 持久性：由redo log 保证。事务提交时先把redo log写入并fsync落盘(先写日志再改数据，WAL)，然后才改数据页；即使此刻断电崩溃，也能用redo
  log重放出未落盘的修改

查询操作需要使用事务嘛？

- 不用显式开启：MySQL默认autocommit=1，单条SELECT本身就是隐式事务，查完自动提交
- 但查询一定运行在事务里：InnoDB每条语句都在事务中执行，隔离级别决定它能读到哪个版本
- 要显式开事务(BEGIN...COMMIT)的场景：
    - 多条语句要原子性：转账的"扣款+入账"必须同成同败，中间失败不能只扣不转
    - 要保持同一份快照：RR下显式开事务，多条查询共用一个read view，前后结果一致
    - 要加锁读占坑：SELECT ... FOR UPDATE / LOCK IN SHARE MODE，防止"查完到改之间"被别人改掉
    - 要控制事务范围：长事务会拖住undo log不释放、撑大回滚段、阻塞purge，事务尽量做短
- 注意：
    - 普通SELECT不加行锁(靠MVCC)，但FOR UPDATE、UPDATE、DELETE会加行锁
    - RR下没走索引的更新可能锁全表(间隙锁)，并发写入容易被堵

## 事务的使用与优化

应用侧保证一致：单库/单机靠数据库事务保证 ACID；跨服务、跨库时一个本地事务覆盖不了全局，只能靠应用层设计保证最终一致（本地事务 + 消息表/MQ + 幂等消费 + 定时补偿对账），而不是硬上 XA（性能差、故障恢复复杂）

### 什么时候开事务

- 能一条 SQL 搞定的绝不显式开事务，autocommit=1 下单条语句本身就是隐式事务
- 只读：只有需要"多张表读同一份快照"时才 BEGIN（RR 下多条查询共用一个 read view）
- 写：涉及多表、多行、多语句必须开，保证原子性
- 跨服务调用不要放进同一个本地事务：本地事务只管本地库，远程调用失败时回滚不了对方，要靠应用侧最终一致（事务消息 / TCC / Saga）

### Spring 声明式事务

```java
@Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED, timeout = 30)
public void transfer(String from, String to, BigDecimal amount) {
    // ...
}
```

常用参数：

- rollbackFor：默认只对 RuntimeException 和 Error 回滚，受检异常不回滚，统一写 rollbackFor = Exception.class
- propagation：REQUIRED(默认，有则加入无则新建) / REQUIRES_NEW(挂起当前事务另起一个，常用于"失败也不影响主流程"的日志) / NESTED(基于 savepoint 局部回滚) / SUPPORTS / NEVER
- timeout：超时抛异常回滚；isolation：指定隔离级别；readOnly：只读标记

@Transactional 失效的经典场景（面试高频）：

- 自调用：同类内部方法互调不走代理，注解不生效 → 拆到别的 Bean、注入自己、AopContext.currentProxy()
- 方法不是 public：Spring 5.x 只支持 public（protected/private 无效），Spring 6 起才放开
- final 方法：CGLIB 无法重写（Boot 2.x 默认 CGLIB 代理）
- 异常被吞：try-catch 吞掉不抛出，事务不回滚
- 抛的是受检异常：默认不回滚，要配 rollbackFor
- 表引擎不支持：MyISAM 没有事务
- 多线程：事务绑定在 ThreadLocal，子线程看不到父线程事务，异步任务要自己重新开
- 传播行为用错：REQUIRES_NEW 频繁使用会额外占用数据库连接，注意连接池大小

### 大事务问题

危害：

- 长时间持锁，阻塞其他事务，锁等待甚至死锁
- undo log 无法 purge，history list 变长，回滚段膨胀
- 主从延迟：大事务的 binlog 在从库重放更久
- 连接被长期占用，连接池耗尽
- 崩溃后回滚慢，恢复时间长

优化：

- 拆成多个小事务分批提交（如每批 500 条）
- 事务内不做 RPC / HTTP 调用、文件读写、发消息（发消息用本地消息表保证最终一致）
- 大批量 UPDATE / DELETE 分批执行，WHERE 条件务必走索引，否则会锁住大量行
- 只读多、并发高的场景用 RC 缩小锁范围，需要一致性快照时再用 RR

### 锁与并发控制

- UPDATE / DELETE / SELECT ... FOR UPDATE 必须命中索引，否则锁的是扫描到的每一行（RR 下还加间隙锁，可能锁全表）
- RR 间隙锁范围大，能用 RC 就用 RC（RC 下除唯一键、外键检查外基本不用间隙锁）
- 减少加锁行数：分批处理、按主键更新、避免大范围条件
- 多表加锁顺序保持一致，降低死锁概率
- 死锁排查：SHOW ENGINE INNODB STATUS\G 看 "LATEST DETECTED DEADLOCK"，MySQL 8.0 用 performance_schema.data_locks / data_lock_waits

### 排查长事务

```sql
-- 当前运行中的事务
SELECT trx_id, trx_state, trx_started, trx_rows_locked, trx_query
FROM information_schema.innodb_trx;

-- 锁等待关系（MySQL 8.0）
SELECT * FROM performance_schema.data_lock_waits;
```


