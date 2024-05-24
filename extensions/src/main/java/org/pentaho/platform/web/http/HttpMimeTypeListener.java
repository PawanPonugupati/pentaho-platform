/*!
 *
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 *
 * Copyright (c) 2002-2018 Hitachi Vantara. All rights reserved.
 *
 */

package org.pentaho.platform.web.http;

import org.pentaho.platform.api.engine.IMimeTypeListener;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Deprecated
public class HttpMimeTypeListener implements IMimeTypeListener {

  private org.pentaho.platform.web.servlet.HttpMimeTypeListener mimeTypeListener;

  @Deprecated
  public HttpMimeTypeListener( final HttpServletRequest request, final HttpServletResponse response ) {
    mimeTypeListener = new org.pentaho.platform.web.servlet.HttpMimeTypeListener( request, response );
  }

  @Deprecated
  public HttpMimeTypeListener( final HttpServletRequest request, final HttpServletResponse response,
                               final String title ) {
    mimeTypeListener = new org.pentaho.platform.web.servlet.HttpMimeTypeListener( request, response, title );
  }

  @Deprecated
  public void setName( String name ) {
    mimeTypeListener.setName( name );
  }

  @Deprecated
  public void setMimeType( final String mimeType ) {
    mimeTypeListener.setMimeType( mimeType );
  }

}
