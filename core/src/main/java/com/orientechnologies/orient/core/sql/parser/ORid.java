/* Generated By:JJTree: Do not edit this line. ORid.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

public class ORid extends SimpleNode {
  protected OInteger cluster;
  protected OInteger position;

  public ORid(int id) {
    super(id);
  }

  public ORid(OrientSql p, int id) {
    super(p, id);
  }

  /** Accept the visitor. **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  @Override
  public String toString(String prefix) {
    return "#" + cluster.getValue() + ":" + position.getValue();
  }
}
/* JavaCC - OriginalChecksum=c2c6d67d7722e29212e438574698d7cd (do not edit this line) */