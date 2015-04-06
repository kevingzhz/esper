/**************************************************************************************
 * Copyright (C) 2006-2015 EsperTech Inc. All rights reserved.                        *
 * http://www.espertech.com/esper                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.core.service;

import com.espertech.esper.core.context.factory.StatementAgentInstanceFactoryResult;
import com.espertech.esper.core.context.mgr.ContextStatePathKey;
import com.espertech.esper.core.service.resource.StatementResourceService;
import com.espertech.esper.pattern.EvalRootState;

/**
 * Statement-level extension services.
 */
public interface StatementExtensionSvcContext
{
    public StatementResourceService getStmtResources();
}