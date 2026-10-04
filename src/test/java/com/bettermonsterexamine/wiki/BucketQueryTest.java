package com.bettermonsterexamine.wiki;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BucketQueryTest
{
	@Test
	public void buildsAPageAtTheGivenOffset()
	{
		BucketQuery q = new BucketQuery("item_id", "page_name", "id");

		assertEquals("bucket('item_id').select('page_name','id').offset(0).limit(5000).run()", q.page(0));
		assertEquals("bucket('item_id').select('page_name','id').offset(5000).limit(5000).run()", q.page(5000));
	}

	@Test
	public void onlyAFullPageCanHaveMoreBehindIt()
	{
		// Bucket clamps a page to 5000 without saying so, so a full page is the only "more" signal.
		assertFalse(BucketQuery.isLastPage(BucketQuery.PAGE_SIZE));
		assertTrue(BucketQuery.isLastPage(BucketQuery.PAGE_SIZE - 1));
		assertTrue(BucketQuery.isLastPage(0));
	}
}
