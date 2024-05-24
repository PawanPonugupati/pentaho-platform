/*!
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
 * Copyright (c) 2002-2021 Hitachi Vantara. All rights reserved.
 */

package org.pentaho.platform.web.servlet;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.pentaho.platform.web.gwt.rpc.AbstractGwtRpc;
import org.pentaho.platform.web.gwt.rpc.IGwtRpcSerializationPolicyCache;
import org.pentaho.platform.web.gwt.rpc.SystemGwtRpc;

import jakarta.servlet.http.HttpServletRequest;

/**
 * This servlet is the traffic cop for GWT services core to the BIServer. See pentahoServices.spring.xml for bean
 * definitions referenced by this servlet.
 */
public class GwtRpcProxyServlet extends AbstractGwtRpcProxyServlet {
  
  public GwtRpcProxyServlet() {
    super();
  }
  
  public GwtRpcProxyServlet( @Nullable IGwtRpcSerializationPolicyCache serializationPolicyCache ) {
    super( serializationPolicyCache );
  }
  
  @NonNull @Override
  protected AbstractGwtRpc getRpc( @NonNull HttpServletRequest httpRequest ) {
    return SystemGwtRpc.getInstance( httpRequest, getSerializationPolicyCache() );
  }
}
