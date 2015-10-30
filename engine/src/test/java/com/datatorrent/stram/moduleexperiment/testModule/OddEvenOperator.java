/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.datatorrent.stram.moduleexperiment.testModule;

import com.datatorrent.api.DefaultInputPort;
import com.datatorrent.api.DefaultOutputPort;
import com.datatorrent.common.util.BaseOperator;

/**
 * Toy OddEven Operator. Separates the stream into Odd and Even integers
 */
public class OddEvenOperator extends BaseOperator
{
  public transient DefaultInputPort<Integer> input = new DefaultInputPort<Integer>() {
    
    @Override
    public void process(Integer tuple)
    {
      if(tuple.intValue() % 2 == 0)
      {
        even.emit(tuple);
      }
      else
      {
        odd.emit(tuple);
      }
    }
  };

  public transient DefaultOutputPort<Integer> odd = new DefaultOutputPort<Integer>();
  public transient DefaultOutputPort<Integer> even = new DefaultOutputPort<Integer>();

}
