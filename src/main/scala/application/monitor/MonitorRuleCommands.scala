package ru.bitec.app.ops
package application.monitor
import application.port.{IdGenerator,MonitorRuleRepository,ResourceRepository,TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.metric.MetricCode
import domain.monitor.{MonitorOperator,MonitorRule}
import java.util.UUID
final case class ListMonitorRules[Tx[_]:MonadThrow](resources:ResourceRepository[Tx],rules:MonitorRuleRepository[Tx]){def execute(o:UUID,r:UUID)=resources.findById(o,r).flatMap(_.fold(none[List[MonitorRule]].pure[Tx])(x=>rules.findByResource(o,r).map(Some(_))))}
final case class CreateMonitorRule[Tx[_]:MonadThrow](resources:ResourceRepository[Tx],rules:MonitorRuleRepository[Tx],ids:IdGenerator[Tx],time:TimeProvider[Tx]){def execute(o:UUID,r:UUID,m:MetricCode,op:MonitorOperator,t:BigDecimal,f:Long,e:Boolean)=if(f<0)new IllegalArgumentException("forSeconds").raiseError[Tx,Option[MonitorRule]] else resources.findById(o,r).flatMap(_.fold(none[MonitorRule].pure[Tx])(_=>for{id<-ids.nextId;n<-time.now;rule=MonitorRule(id,o,r,m,op,t,f,e,n,n);_<-rules.save(rule)}yield Some(rule)))}
final case class UpdateMonitorRule[Tx[_]:MonadThrow](rules:MonitorRuleRepository[Tx],time:TimeProvider[Tx]){def execute(o:UUID,id:UUID,m:MetricCode,op:MonitorOperator,t:BigDecimal,f:Long,e:Boolean)=if(f<0)new IllegalArgumentException("forSeconds").raiseError[Tx,Option[MonitorRule]] else rules.findById(o,id).flatMap(_.fold(none[MonitorRule].pure[Tx])(old=>time.now.flatMap(n=>{val x=old.copy(metricCode=m,operator=op,threshold=t,forSeconds=f,enabled=e,updatedAt=n);rules.save(x).as(Some(x))})))}
