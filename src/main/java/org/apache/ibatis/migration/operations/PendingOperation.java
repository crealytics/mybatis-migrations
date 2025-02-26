/**
 *    Copyright 2010-2020 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.migration.operations;

import java.io.PrintStream;
import java.io.Reader;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.migration.Change;
import org.apache.ibatis.migration.ConnectionProvider;
import org.apache.ibatis.migration.MigrationException;
import org.apache.ibatis.migration.MigrationLoader;
import org.apache.ibatis.migration.hook.HookContext;
import org.apache.ibatis.migration.hook.MigrationHook;
import org.apache.ibatis.migration.options.DatabaseOperationOption;
import org.apache.ibatis.migration.utils.Util;

public final class PendingOperation extends DatabaseOperation {

  public PendingOperation operate(ConnectionProvider connectionProvider,
      ConnectionProvider migrationLogConnectionProvider, MigrationLoader migrationsLoader,
      DatabaseOperationOption option, PrintStream printStream) {
    return operate(connectionProvider, migrationLogConnectionProvider, migrationsLoader, option, printStream, null);
  }

  public PendingOperation operate(ConnectionProvider connectionProvider,
      ConnectionProvider migrationLogConnectionProvider, MigrationLoader migrationsLoader,
      DatabaseOperationOption option, PrintStream printStream, MigrationHook hook) {
    try (Connection con = connectionProvider.getConnection();
        Connection migrationLogCon = migrationLogConnectionProvider.getConnection()) {
      if (option == null) {
        option = new DatabaseOperationOption();
      }
      if (!changelogExists(migrationLogCon, option)) {
        throw new MigrationException("Change log doesn't exist, no migrations applied.  Try running 'up' instead. "
            + "If you manage the change log in a separate db, the log table has to be created manually.");
      }
      List<Change> pending = getPendingChanges(migrationLogCon, migrationsLoader, option);
      int stepCount = 0;
      Map<String, Object> hookBindings = new HashMap<String, Object>();
      println(printStream, "WARNING: Running pending migrations out of order can create unexpected results.");
      Reader scriptReader = null;
      try {
        ScriptRunner runner = getScriptRunner(con, option, printStream);
        for (Change change : pending) {
          if (stepCount == 0 && hook != null) {
            hookBindings.put(MigrationHook.HOOK_CONTEXT, new HookContext(connectionProvider, runner, null));
            hook.before(hookBindings);
          }
          if (hook != null) {
            hookBindings.put(MigrationHook.HOOK_CONTEXT, new HookContext(connectionProvider, runner, change.clone()));
            hook.beforeEach(hookBindings);
          }
          println(printStream, Util.horizontalLine("Applying: " + change.getFilename(), 80));
          scriptReader = migrationsLoader.getScriptReader(change, false);
          runner.runScript(scriptReader);
          insertChangelog(change, migrationLogCon, option);
          println(printStream);
          if (hook != null) {
            hookBindings.put(MigrationHook.HOOK_CONTEXT, new HookContext(connectionProvider, runner, change.clone()));
            hook.afterEach(hookBindings);
          }
          stepCount++;
        }
        if (stepCount > 0 && hook != null) {
          hookBindings.put(MigrationHook.HOOK_CONTEXT, new HookContext(connectionProvider, runner, null));
          hook.after(hookBindings);
        }
        return this;
      } catch (Exception e) {
        throw new MigrationException("Error executing command.  Cause: " + e, e);
      } finally {
        if (scriptReader != null) {
          scriptReader.close();
        }
      }
    } catch (Throwable e) {
      while (e instanceof MigrationException) {
        e = e.getCause();
      }
      throw new MigrationException("Error executing command.  Cause: " + e, e);
    }
  }

  private List<Change> getPendingChanges(Connection con, MigrationLoader migrationsLoader,
      DatabaseOperationOption option) {
    List<Change> pending = new ArrayList<Change>();
    List<Change> migrations = migrationsLoader.getMigrations();
    List<Change> changelog = getChangelog(con, option);
    for (Change change : migrations) {
      int index = changelog.indexOf(change);
      if (index < 0) {
        pending.add(change);
      }
    }
    Collections.sort(pending);
    return pending;
  }
}
