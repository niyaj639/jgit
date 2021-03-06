/*
 * Copyright (C) 2009-2010, Google Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.http.server;

import static org.eclipse.jgit.util.HttpSupport.ENCODING_GZIP;
import static org.eclipse.jgit.util.HttpSupport.ENCODING_X_GZIP;
import static org.eclipse.jgit.util.HttpSupport.HDR_ACCEPT_ENCODING;
import static org.eclipse.jgit.util.HttpSupport.HDR_CONTENT_ENCODING;
import static org.eclipse.jgit.util.HttpSupport.HDR_ETAG;
import static org.eclipse.jgit.util.HttpSupport.TEXT_PLAIN;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/** Common utility functions for servlets. */
public final class ServletUtils {
	/** Request attribute which stores the {@link Repository} instance. */
	public static final String ATTRIBUTE_REPOSITORY = "org.eclipse.jgit.Repository";

	/** Request attribute storing either UploadPack or ReceivePack. */
	public static final String ATTRIBUTE_HANDLER = "org.eclipse.jgit.transport.UploadPackOrReceivePack";

	/**
	 * Get the selected repository from the request.
	 *
	 * @param req
	 *            the current request.
	 * @return the repository; never null.
	 * @throws IllegalStateException
	 *             the repository was not set by the filter, the servlet is
	 *             being invoked incorrectly and the programmer should ensure
	 *             the filter runs before the servlet.
	 * @see #ATTRIBUTE_REPOSITORY
	 */
	public static Repository getRepository(final ServletRequest req) {
		Repository db = (Repository) req.getAttribute(ATTRIBUTE_REPOSITORY);
		if (db == null)
			throw new IllegalStateException(HttpServerText.get().expectedRepositoryAttribute);
		return db;
	}

	/**
	 * Open the request input stream, automatically inflating if necessary.
	 * <p>
	 * This method automatically inflates the input stream if the request
	 * {@code Content-Encoding} header was set to {@code gzip} or the legacy
	 * {@code x-gzip}.
	 *
	 * @param req
	 *            the incoming request whose input stream needs to be opened.
	 * @return an input stream to read the raw, uncompressed request body.
	 * @throws IOException
	 *             if an input or output exception occurred.
	 */
	public static InputStream getInputStream(final HttpServletRequest req)
			throws IOException {
		InputStream in = req.getInputStream();
		final String enc = req.getHeader(HDR_CONTENT_ENCODING);
		if (ENCODING_GZIP.equals(enc) || ENCODING_X_GZIP.equals(enc))
			in = new GZIPInputStream(in);
		else if (enc != null)
			throw new IOException(MessageFormat.format(HttpServerText.get().encodingNotSupportedByThisLibrary
					, HDR_CONTENT_ENCODING, enc));
		return in;
	}

	/**
	 * Consume the entire request body, if one was supplied.
	 *
	 * @param req
	 *            the request whose body must be consumed.
	 */
	public static void consumeRequestBody(HttpServletRequest req) {
		if (0 < req.getContentLength() || isChunked(req)) {
			try {
				consumeRequestBody(req.getInputStream());
			} catch (IOException e) {
				// Ignore any errors obtaining the input stream.
			}
		}
	}

	static boolean isChunked(HttpServletRequest req) {
		return "chunked".equals(req.getHeader("Transfer-Encoding"));
	}

	/**
	 * Consume the rest of the input stream and discard it.
	 *
	 * @param in
	 *            the stream to discard, closed if not null.
	 */
	public static void consumeRequestBody(InputStream in) {
		if (in == null)
			return;
		try {
			while (0 < in.skip(2048) || 0 <= in.read()) {
				// Discard until EOF.
			}
		} catch (IOException err) {
			// Discard IOException during read or skip.
		} finally {
			try {
				in.close();
			} catch (IOException err) {
				// Discard IOException during close of input stream.
			}
		}
	}

	/**
	 * Send a plain text response to a {@code GET} or {@code HEAD} HTTP request.
	 * <p>
	 * The text response is encoded in the Git character encoding, UTF-8.
	 * <p>
	 * If the user agent supports a compressed transfer encoding and the content
	 * is large enough, the content may be compressed before sending.
	 * <p>
	 * The {@code ETag} and {@code Content-Length} headers are automatically set
	 * by this method. {@code Content-Encoding} is conditionally set if the user
	 * agent supports a compressed transfer. Callers are responsible for setting
	 * any cache control headers.
	 *
	 * @param content
	 *            to return to the user agent as this entity's body.
	 * @param req
	 *            the incoming request.
	 * @param rsp
	 *            the outgoing response.
	 * @throws IOException
	 *             the servlet API rejected sending the body.
	 */
	public static void sendPlainText(final String content,
			final HttpServletRequest req, final HttpServletResponse rsp)
			throws IOException {
		final byte[] raw = content.getBytes(Constants.CHARACTER_ENCODING);
		rsp.setContentType(TEXT_PLAIN);
		rsp.setCharacterEncoding(Constants.CHARACTER_ENCODING);
		send(raw, req, rsp);
	}

	/**
	 * Send a response to a {@code GET} or {@code HEAD} HTTP request.
	 * <p>
	 * If the user agent supports a compressed transfer encoding and the content
	 * is large enough, the content may be compressed before sending.
	 * <p>
	 * The {@code ETag} and {@code Content-Length} headers are automatically set
	 * by this method. {@code Content-Encoding} is conditionally set if the user
	 * agent supports a compressed transfer. Callers are responsible for setting
	 * {@code Content-Type} and any cache control headers.
	 *
	 * @param content
	 *            to return to the user agent as this entity's body.
	 * @param req
	 *            the incoming request.
	 * @param rsp
	 *            the outgoing response.
	 * @throws IOException
	 *             the servlet API rejected sending the body.
	 */
	public static void send(byte[] content, final HttpServletRequest req,
			final HttpServletResponse rsp) throws IOException {
		content = sendInit(content, req, rsp);
		final OutputStream out = rsp.getOutputStream();
		try {
			out.write(content);
			out.flush();
		} finally {
			out.close();
		}
	}

	private static byte[] sendInit(byte[] content,
			final HttpServletRequest req, final HttpServletResponse rsp)
			throws IOException {
		rsp.setHeader(HDR_ETAG, etag(content));
		if (256 < content.length && acceptsGzipEncoding(req)) {
			content = compress(content);
			rsp.setHeader(HDR_CONTENT_ENCODING, ENCODING_GZIP);
		}
		rsp.setContentLength(content.length);
		return content;
	}

	static boolean acceptsGzipEncoding(final HttpServletRequest req) {
		return acceptsGzipEncoding(req.getHeader(HDR_ACCEPT_ENCODING));
	}

	static boolean acceptsGzipEncoding(String accepts) {
		if (accepts == null)
			return false;

		int b = 0;
		while (b < accepts.length()) {
			int comma = accepts.indexOf(',', b);
			int e = 0 <= comma ? comma : accepts.length();
			String term = accepts.substring(b, e).trim();
			if (term.equals(ENCODING_GZIP))
				return true;
			b = e + 1;
		}
		return false;
	}

	private static byte[] compress(final byte[] raw) throws IOException {
		final int maxLen = raw.length + 32;
		final ByteArrayOutputStream out = new ByteArrayOutputStream(maxLen);
		final GZIPOutputStream gz = new GZIPOutputStream(out);
		gz.write(raw);
		gz.finish();
		gz.flush();
		return out.toByteArray();
	}

	private static String etag(final byte[] content) {
		final MessageDigest md = Constants.newMessageDigest();
		md.update(content);
		return ObjectId.fromRaw(md.digest()).getName();
	}

	static String identify(Repository git) {
		if (git instanceof DfsRepository) {
			return ((DfsRepository) git).getDescription().getRepositoryName();
		} else if (git.getDirectory() != null) {
			return git.getDirectory().getPath();
		}
		return "unknown";
	}

	private ServletUtils() {
		// static utility class only
	}
}
