require "dry/inflector"

class Invoice
  def initialize(amounts)
    @amounts = amounts
  end

  def table_name
    Dry::Inflector.new.tableize(self.class.name)
  end

  def total
    # Unused on purpose, so that both ruby-lsp and RuboCop report it.
    count = @amounts.size
    @amounts.sum
  end
end
