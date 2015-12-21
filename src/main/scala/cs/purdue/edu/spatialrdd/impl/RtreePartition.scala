package cs.purdue.edu.spatialrdd.impl

import cs.purdue.edu.spatialindex.rtree._
import cs.purdue.edu.spatialrdd._

import org.apache.spark.Logging

import scala.collection.immutable.HashMap


/**
 * the key is the location, and value is the related data like text
 */

import scala.reflect.ClassTag

/**
 * Created by merlin on 8/4/15.
 */

class RtreePartition[K, V]
(protected val tree: RTree[V])
(override implicit val kTag: ClassTag[K],
 override implicit val vTag: ClassTag[V]
 )
  extends SpatialRDDPartition[K,V] with Logging
{

  override def size: Long = tree.size.asInstanceOf[Long]

  override def apply(k: K): Option[V] = {
  tree.searchPoint(Util.toPoint(k)) match
  {
      case null=>None
      case x:Entry[V]=>Some(x.value)
  }

  }

  def isDefined(k: K): Boolean = tree.searchPoint(Util.toPoint(k)) != null

  override def iterator: Iterator[(K, V)] ={

    this.tree.entries.map(
      kvs=>(kvs.geom.asInstanceOf[K],kvs.value)
    )

  }

  /**
   * constructor for a new rtree partition
   */
  protected def withMap(map: RTree[V]): RtreePartition[K, V] = {

    new RtreePartition(map)

  }

  /**
   * Gets the values corresponding to the specified keys, if any. those keys can be the two dimensional object
   */
  override def multiget(ks: Iterator[K]): Iterator[(K, V)]=
  {
    ks.flatMap { k => this(k).map(v => (k, v)) }
  }


  override def delete(ks: Iterator[Entry[V]]): SpatialRDDPartition[K, V]=
  {
    var newMap = this.tree

    newMap=newMap.removeAll(ks.toIterable)

    this.withMap(newMap)
  }


  /**
   * Updates the keys in `kvs` to their corresponding values generated by running `f` on old and new
   * values, if an old value exists, or `z` otherwise. Returns a new IndexedRDDPartition that
   * reflects the modification.
   */
  override def multiput[U](kvs: Iterator[(K, U)],
                           z: (K, U) => V,
                           f: (K, V, U) => V): SpatialRDDPartition[K, V] =
  {

    var newMap = this.tree

    for (ku <- kvs)
    {
      val oldpoint=Util.toPoint(ku._1)

      val oldV = newMap.searchPoint(oldpoint)

      val newV = if (oldV == null) z(ku._1, ku._2) else f(ku._1, oldV.value, ku._2)

      val newEntry=Util.toEntry(ku._1, newV)

      newMap=newMap.insert(oldpoint, newV)

    }

    this.withMap(newMap)
  }


  /**
   * this is used for range search
   * @param box
   * @param z
   * @tparam U
   * @return
   */
  def filter[U](box:U, z:Entry[V]=>Boolean):Iterator[(K, V)]=
  {
    val newMap = this.tree

    val ret=newMap.search(box.asInstanceOf[Box],z)
    //println("search XXXXXXXXXXXXXXXXXXXXXX"+ret.size+" "+newMap.size)
    ret.map(element=>(element.geom.asInstanceOf[K], element.value)).toIterator

  }

  /**
   * this is used for knn search over local data
   * @param entry
   * @param k
   * @param z
   * @tparam U
   * @return
   */
  def knnfilter[U](entry:U, k:Int, z:Entry[V]=>Boolean):Iterator[(K,V, Double)]=
  {
    entry match
    {
      case e:Point => this.tree.nearestK(e,k,z).map
        {
            case(dist,element)=>
              (element.geom.asInstanceOf[K],element.value,dist)
      }.toIterator
    }
  }

  override def sjoin[U: ClassTag]
  (other: SpatialRDDPartition[K, U])
  (f: (K, V) => V): SpatialRDDPartition[K, V] = sjoin(other.iterator)(f)

  /**
   *the U need to be box
   */
  override def sjoin[U: ClassTag]
  (other: Iterator[(K, U)])
  (f: (K, V) => V): SpatialRDDPartition[K, V] = {

    val newMap = this.tree
    /**
     * below is dual tree based sjoin
     */
    var retmap=new HashMap[K,V]

    //option2: build the tree for the query box approach
    val value=1
    val boxes=other.map
    {
      case(point,b:Box)=>Entry(b,value.asInstanceOf[V])
    }

    val boxtree=RTree(boxes)

    //Todo: return the output as the (box,entry) type, then do the reduce to merge the result
    //newMap.joins(boxtree)

    newMap.join(boxtree).map(
      e=>
      retmap = retmap + (e.geom.asInstanceOf[K] -> e.value)
    )

    boxtree.cleanTree()
    new SMapPartition(retmap)
  }

  /**
   * range join operators
   * @param other
   * @param f
   * @tparam U
   * @return
   */
  override def rjoin[U: ClassTag, U2:ClassTag]
  (other: SpatialRDDPartition[K, U])
  (f: (Iterator[(K,V)]) => U2,
   f2:(U2,U2)=>U2):  Iterator[(U, U2)] = rjoin(other.iterator)(f,f2)


  def rjoin[U: ClassTag, U2:ClassTag]
  (other: Iterator[(K, U)])
  (f: (Iterator[(K,V)]) => U2,
   f2:(U2,U2)=>U2):  Iterator[(U, U2)]=
  {

    val newMap = this.tree
    /**
     * below is dual tree based sjoin
     */
    var retmap=new HashMap[K,V]

    //option2: build the tree for the query box approach
    val value=1
    val boxes=other.map
    {
      case(point,b:Box)=>Entry(b,value.asInstanceOf[V])
    }

    val boxtree=RTree(boxes)

    val ret=newMap.joins[K,U,U2](boxtree)(f,f2).toIterator

    boxtree.cleanTree()

    ret

  }


}

private[spatialrdd] object RtreePartition {

  def apply[K: ClassTag, V: ClassTag]
  (iter: Iterator[(K, V)]) =
    apply[K, V, V](iter, (id, a) => a, (id, a, b) => b)

  def apply[K: ClassTag, U: ClassTag, V: ClassTag]
  (iter: Iterator[(K, V)], z: (K, U) => V, f: (K, V, U) => V)
  : SpatialRDDPartition[K, V] =
  {
    val map = RTree(iter.map{ case(k, v) => Util.toEntry(k,v)})
    //this is optional for the spatial join
    map.sortInternalnode()
    new RtreePartition(map)
  }

}