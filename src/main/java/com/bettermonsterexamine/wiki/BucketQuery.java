package com.bettermonsterexamine.wiki;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One {@code select} over a Bucket, built a page at a time.
 *
 * <p>Bucket serves at most {@link #PAGE_SIZE} rows per query and <b>clamps a larger limit
 * silently</b>: {@code limit(6000)} returns 5000 rows, and the response carries no error, warning
 * or continuation to say more exist. The only signal is a page that comes back full, so callers
 * request {@link #page} at increasing offsets until {@link #isLastPage} says otherwise.
 */
public final class BucketQuery
{
	public static final int PAGE_SIZE = 5000;

	private final String bucket;
	private final List<String> fields;

	public BucketQuery(String bucket, String... fields)
	{
		this.bucket = bucket;
		this.fields = Collections.unmodifiableList(Arrays.asList(fields));
	}

	/** The query for the page starting at {@code offset}, for the API's {@code query} parameter. */
	public String page(int offset)
	{
		StringBuilder q = new StringBuilder("bucket('").append(bucket).append("').select(");
		for (int i = 0; i < fields.size(); i++)
		{
			if (i > 0)
			{
				q.append(',');
			}
			q.append('\'').append(fields.get(i)).append('\'');
		}
		return q.append(").offset(").append(offset).append(").limit(").append(PAGE_SIZE).append(").run()")
			.toString();
	}

	/** True when a page of {@code rows} is the last: only a full page can have more behind it. */
	public static boolean isLastPage(int rows)
	{
		return rows < PAGE_SIZE;
	}
}
