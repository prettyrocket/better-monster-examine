/**
 * A small client for a MediaWiki {@code api.php}, written to be copied into other projects as a
 * folder: it depends only on OkHttp, Gson and the JDK, with no RuneLite, Lombok or singletons.
 *
 * <ul>
 * <li>{@link com.bettermonsterexamine.wiki.WikiClient}: blocking GETs as JSON, given the API URL
 * and a User-Agent with contact details.</li>
 * <li>{@link com.bettermonsterexamine.wiki.WikiCache}: one cached file with a maximum age, which
 * can remember the page revision it came from and skip re-downloading an unedited page.</li>
 * <li>{@link com.bettermonsterexamine.wiki.BucketQuery}: a Bucket {@code select}, paged past the
 * silent 5000-row cap.</li>
 * <li>{@link com.bettermonsterexamine.wiki.TitleResolver}: 50-title batches, and which page
 * answered for each title after normalisation and redirects.</li>
 * <li>{@link com.bettermonsterexamine.wiki.WikitextTemplates}: templates, parameters and
 * {@code {{efn}}} footnotes read out of raw wikitext.</li>
 * </ul>
 *
 * <p>Two rules every caller follows. Run requests on one background thread, so they go out one at a
 * time as MediaWiki's API etiquette asks. Apply a download <b>before</b> writing it to the cache, so a
 * response that fails to parse never replaces a good file. {@link com.bettermonsterexamine.wiki.WikiApi}
 * is the one plugin-specific file: the OSRS Wiki's URL and this plugin's User-Agent.
 */
package com.bettermonsterexamine.wiki;
