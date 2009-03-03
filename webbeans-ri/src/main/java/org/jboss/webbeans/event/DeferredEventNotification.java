/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,  
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.webbeans.event;

import javax.event.Observer;

/**
 * A task that will notify the observer of a specific event at some
 * future time.
 * 
 * @author David Allen
 */
public class DeferredEventNotification<T> implements Runnable
{
   // The observer
   private Observer<T> observer;
   // The event object
   private T event;

   /**
    * Creates a new deferred event notifier.
    * 
    * @param observer The observer to be notified
    * @param event The event being fired
    */
   public DeferredEventNotification(T event, Observer<T> observer)
   {
      this.observer = observer;
      this.event = event;
   }

   @Override
   public void run()
   {
      observer.notify(event);
   }
}
