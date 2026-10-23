package cn.shopledger

import cn.shopledger.data.*
import org.junit.Assert.*
import org.junit.Test

class WorkEntryGroupTest {
    private val row=WorkEntry("first","employee","task","服务",100,200,1000,"老牛",2000)
    @Test fun wholeAndFractionalRowsFromOneSubmissionBecomeOneDetail() {
        val rows=listOf(row,row.copy(id="second"),row.copy(id="half",quantityTenths=5))
        val group=groupWorkEntries(rows).single()
        assertEquals(25,group.quantityTenths)
        assertEquals(250L,group.wageCents)
        assertEquals(500L,group.revenueCents)
        assertEquals(rows.map { it.id },group.ids)
        assertEquals(rows,group.rows)
    }
    @Test fun differentSubmissionsNotesPricesAndPayrollMembershipNeverMix() {
        val variants=listOf(row.copy(id="time",createdAt=2001),row.copy(id="work",completedAt=1001),
            row.copy(id="note",note="老吴"),row.copy(id="wage",wageCentsSnapshot=200),
            row.copy(id="revenue",revenueCentsSnapshot=300),row.copy(id="name",taskNameSnapshot="旧服务"),
            row.copy(id="employee",employeeId="other"),row.copy(id="task",taskTypeId="other"),
            row.copy(id="slip",settlementId="settled"))
        val groups=groupWorkEntries(listOf(row,row.copy(id="same-batch"))+variants)
        assertEquals(10,groups.size)
        assertEquals(20,groups.first().quantityTenths)
        assertEquals(listOf(row.id)+variants.map { it.id },groups.map { it.id })
        assertTrue(groupWorkEntries(emptyList()).isEmpty())
    }
    @Test fun groupingAddsSavedRoundedAmountsInsteadOfRepricingHistory() {
        val half=row.copy(wageCentsSnapshot=1,revenueCentsSnapshot=3,quantityTenths=5)
        val group=groupWorkEntries(listOf(half,half.copy(id="other-half"))).single()
        assertEquals(10,group.quantityTenths)
        assertEquals(2L,group.wageCents)
        assertEquals(4L,group.revenueCents)
    }
}
