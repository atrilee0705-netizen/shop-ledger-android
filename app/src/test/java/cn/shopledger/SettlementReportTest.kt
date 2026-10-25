package cn.shopledger

import cn.shopledger.backup.SettlementReport
import cn.shopledger.data.*
import org.junit.Assert.*
import org.junit.Test

class SettlementReportTest {
    private val slip=Settlement("s",1000,5000,6000,true)
    private val employees=listOf(Employee("e1","张三",true,1),Employee("e2","李四",false,1))
    private fun row(id: String,employee: String,task: String,wage: Long,revenue: Long,quantity: Int=10,owner: String?="s")=
        WorkEntry(id,employee,task,task,wage,revenue,2000,"",3000,owner,quantity)
    private val work=listOf(row("1","e1","A",100,300),row("2","e1","A",100,300,5),
        row("3","e1","B",200,500),row("4","e1","B",200,500),row("5","e2","A",100,300),
        row("6","e2","A",100,300),row("7","e2","A",100,300))
    private val wages=listOf(SettlementItem("s","e1",550),SettlementItem("s","e2",300))
    @Test fun eachEmployeesProjectsAndWholeShopRevenueUseOnlyThisPayroll() {
        val report=SettlementReport.text(slip,employees,wages+SettlementItem("other","e1",9999),
            work+row("pending","e1","未来任务",9999,9999,owner=null)+row("other","e1","别期任务",9999,9999,owner="other"),emptyList())
        assertTrue(report.contains("张三：¥5.50\n  A：1.5次\n  B：2次"))
        assertTrue(report.contains("李四：¥3.00\n  A：3次"))
        assertTrue(report.contains("全员工资：¥8.50"))
        assertTrue(report.contains("店铺总营业额：¥23.50"))
        assertFalse(report.contains("未来任务")); assertFalse(report.contains("别期任务"))
    }
    @Test fun correctionsRefreshProjectCountsAndRevenueTogether() {
        val correction=SettlementAdjustment("c","s",850,950,"补记B 0.5次",7000)
        val report=SettlementReport.text(slip.copy(revision=2),employees,listOf(wages[0].copy(amountCents=650),wages[1]),
            work+row("added","e1","B",200,500,5),listOf(correction))
        assertTrue(report.contains("第2版（修正版，请以本版为准）"))
        assertTrue(report.contains("B：2.5次"))
        assertTrue(report.contains("全员工资：¥9.50"))
        assertTrue(report.contains("店铺总营业额：¥26.00"))
        assertTrue(report.contains("合计由¥8.50改为¥9.50"))
    }
    @Test fun historicTaskNamesAndDuplicateTaskIdsStayDistinctWhilePricesDoNotSplitCounts() {
        val rows=listOf(work[0],work[1].copy(wageCentsSnapshot=500),work[0].copy(id="renamed",taskNameSnapshot="改名后的A"),
            work[0].copy(id="duplicate",taskTypeId="other-task"))
        assertEquals(listOf(TaskQuantity("A","A",15),TaskQuantity("A","改名后的A",10),TaskQuantity("other-task","A",10)),taskQuantities(rows))
    }
    @Test fun zeroPayrollAndRoundedFractionalRevenueAreNotRepriced() {
        val empty=SettlementReport.text(slip,employees,emptyList(),emptyList(),emptyList())
        assertTrue(empty.contains("全员工资：¥0.00")); assertTrue(empty.contains("店铺总营业额：¥0.00"))
        val halves=listOf(row("a","e1","半次",1,3,5),row("b","e1","半次",1,3,5))
        val report=SettlementReport.text(slip,employees,listOf(SettlementItem("s","e1",2)),halves,emptyList())
        assertTrue(report.contains("半次：1次"))
        assertTrue(report.contains("全员工资：¥0.02")); assertTrue(report.contains("店铺总营业额：¥0.04"))
    }
}
